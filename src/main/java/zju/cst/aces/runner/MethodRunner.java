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

        // Repair Phase: use ChatUniTest's own LLM+rule repair (phase.repairTest), NOT the
        // YateFixer — YateFixer.fixGeneratedTest (com.mkonst yate-java) recurses and crashes
        // the JVM (fatal hs_err) on the headless Defects4J path, killing the whole target.
        // This mirrors the MUTAPRunner repair loop: re-prompt with the validation error up to
        // maxRounds and export as soon as the test compiles+passes. Only VALIDATED tests are
        // exported, so measurement never sees an uncompilable suite.
        for (int round = 1; round < config.getMaxRounds(); round++) {
            promptInfo.setRound(round);
            phase.repairTest(pc);
            if (phase.validateTest(pc)) {
                exportRecord(pc.getPromptInfo(), classInfo, num);
                return true;
            }
        }
        config.getLogger().info("Test for method < " + methodInfo.methodName
                + " > did not validate after " + config.getMaxRounds() + " repair round(s)");
        return false;
    }
}