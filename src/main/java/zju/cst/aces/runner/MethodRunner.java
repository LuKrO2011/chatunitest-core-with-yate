package zju.cst.aces.runner;

import com.mkonst.types.YateResponse;
import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.dto.*;
import zju.cst.aces.runner.ClassRunner;
import zju.cst.aces.util.JsonResponseProcessor;
import zju.cst.aces.util.yate.YateFixer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MethodRunner extends ClassRunner {

    public MethodInfo methodInfo;

    public MethodRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName);
        this.methodInfo = methodInfo;
    }

    @Override
    public void start() throws IOException {
        if (!config.isStopWhenSuccess() && config.isEnableMultithreading()) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getTestNumber());
            List<Future<String>> futures = new ArrayList<>();
            for (int num = 0; num < config.getTestNumber(); num++) {
                int finalNum = num;
                Callable<String> callable = () -> {
                    startRounds(finalNum);
                    return "";
                };
                Future<String> future = executor.submit(callable);
                futures.add(future);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));

            for (Future<String> future : futures) {
                try {
                    String result = future.get();
                    System.out.println(result);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

            executor.shutdown();
        } else {
            for (int num = 0; num < config.getTestNumber(); num++) {
                boolean result = startRounds(num); //todo
                if (result && config.isStopWhenSuccess()) {
                    break;
                }
            }
        }
    }

    public boolean startRounds(final int num) throws IOException {

        Phase phase = PhaseImpl.createPhase(config);

        // Prompt Construction Phase
        PromptConstructorImpl pc = phase.generatePrompt(classInfo, methodInfo,num);
        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setRound(0);

        // Test Generation Phase
        phase.generateTest(pc);

        // Validation
        if (phase.validateTest(pc)) {
            exportRecord(pc.getPromptInfo(), classInfo, num);

            return true;
        }

        // Validation and Repair Phase (replaced) using YATE
        System.out.println("Trying to fix test using YATE: " + promptInfo.fullTestName);

        YateFixer yateFixer = new YateFixer(config);

        YateResponse yateResponse = yateFixer.fixGeneratedTest(fullClassName, classInfo, promptInfo);

        // Update ChatUniTest's data structure that holds the generated test class content
        pc.getPromptInfo().setUnitTest(yateResponse.getTestClassContainer().getCompleteContent());

        exportRecord(pc.getPromptInfo(), classInfo, num);
        return !yateResponse.getHasChanges();
    }
}