package zju.cst.aces.util.yate;

import com.aallam.openai.api.chat.ChatMessage;
import com.mkonst.analysis.ClassContainer;
import com.mkonst.analysis.JavaClassContainer;
import com.mkonst.helpers.*;
import com.mkonst.providers.ChatHistoryBuilder;
import com.mkonst.runners.YateJavaRunner;
import com.mkonst.types.YateResponse;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.config.YateConfig;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.PromptInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class YateFixer {
    private final Config config;
    private final String repositoryPath;

    public YateFixer(Config config) {
        this.config = config;
        this.repositoryPath = config.getProject().getBasedir().getAbsolutePath();
    }

    public YateResponse fixGeneratedTest(String fullClassName, ClassInfo classInfo, PromptInfo promptInfo) {
        String testClassName = getTestClassName(promptInfo);

        // Locate class under test path (used by YATE to generate and analyze related files)
        String cutPath = YateJavaUtils.INSTANCE.getClassFileFromPackage(repositoryPath, fullClassName);

        // Create a YATE Class Container to hold the CUT info
        JavaClassContainer cutContainer = new JavaClassContainer(classInfo.className, classInfo.compilationUnitCode);
        cutContainer.getPaths().setCut(cutPath);

        // Create a YATE Test class container to hold the info of the generated test class
        JavaClassContainer testContainer = new JavaClassContainer(testClassName, promptInfo.getUnitTest());
        testContainer.setPathsFromCut(cutContainer);
        testContainer.toTestFile();

        // Generate "potential" LLM history (hence the class information is available by YATE)
        List<ChatMessage> history = generateLLMConversation(classInfo.compilationUnitCode, promptInfo.getUnitTest());

        // Create a YateResponse object, to allow YATE to append modifications between LLM-interactions
        YateResponse yateResponse = new YateResponse(testContainer, history, false);

        // Initialize a new runner to invoke YATE components
        YateJavaRunner runner = new YateJavaRunner(
                repositoryPath,
                config.yate.useOracleFixing,
                config.yate.outputDirectory,
                config.yate.modelName
        );

        try {
            // Invoke YATE's compilation fixing component
            runner.fixGeneratedTestClass(cutContainer, yateResponse);
            yateResponse.getTestClassContainer().toTestFile();

            // Invoke YATE's oracle fixing component (if enabled)
            if (config.yate.useOracleFixing) {
                runner.fixOraclesInTestClass(yateResponse);
                yateResponse.getTestClassContainer().toTestFile();
            }
        } catch (Exception ex) {
            // Move generated test class to output directory (if enabled)
            if (config.yate.outputDirectory != null) {
                moveGeneratedTestsToOutputDirectory(yateResponse.getTestClassContainer(), promptInfo);
            }

            yateResponse.setHasChanges(true);
            return yateResponse;
        }

        // Move generated test class to output directory (if enabled)
        if (config.yate.outputDirectory != null) {
            moveGeneratedTestsToOutputDirectory(yateResponse.getTestClassContainer(), promptInfo);
        }

        config.logger.info("YATE COMPILATION FIXING REQUESTS: " + runner.getNrRequests().getCompilationFixing());
        config.logger.info("YATE ORACLE FIXING REQUESTS: " + runner.getNrRequests().getOracleFixing());
        runner.resetNrRequests();

        // Has Changed flag will hold whether the YateResponse has any failures or not
        yateResponse.setHasChanges(hasFailed(yateResponse));
        return yateResponse;
    }

    private String getTestClassName(PromptInfo promptInfo) {
        String[] testClassSplit = promptInfo.fullTestName.split("\\.");

        return testClassSplit[testClassSplit.length - 1];
    }

    private boolean hasFailed(YateResponse yateResponse) {
        try {
            return YateCodeUtils.INSTANCE.countTestMethods(yateResponse.getTestClassContainer()) <= 0;
        } catch (Exception e) {
            YateConsole.INSTANCE.error("An error occurred when counting test methods");
            YateConsole.INSTANCE.error(e.getMessage());

            return true;
        }
    }

    private List<ChatMessage> generateLLMConversation(String classContent, String testClassContent) {
        ChatHistoryBuilder historyBuilder = new ChatHistoryBuilder();
        historyBuilder.setSystemPrompt("system");

        // Prepare user prompt
        HashMap<String, String> userPromptVars = new HashMap<>();
        userPromptVars.put("CLASS_CONTENT", classContent);
        historyBuilder.addUserPrompt("ablation_generate_simple", userPromptVars);

        // Prepare assistant response
        HashMap<String, String> assistantPromptVars = new HashMap<>();
        assistantPromptVars.put("CODE_LANG", "java");
        assistantPromptVars.put("RESPONSE", testClassContent);

        historyBuilder.addAssistantPrompt("code_response", assistantPromptVars);

        return historyBuilder.build();
    }

    private void moveGeneratedTestsToOutputDirectory(ClassContainer classContainer, PromptInfo promptInfo) {
        if (config.yate.outputDirectory == null) {
            return;
        }

        // Default
        if(!config.phaseType.equals("HITS")) {
            YateUtils.INSTANCE.moveGeneratedTestClass(classContainer, config.yate.outputDirectory);
        } else {

            // For HITS make sure that the filename has also the slice number
            try {
                String sourcePath = classContainer.getPaths().getTestClass();
                File sourceFile = new File(sourcePath);
                String destinationName = sourceFile.getName().replace("Test", "Test_slice" + promptInfo.getSliceNum());

                String directoriesAfterRepository =
                        classContainer.getPaths()
                                .getTestClass()
                                .substring(classContainer.getPaths().getTestClass().indexOf("src/test") + "src/test".length());

                directoriesAfterRepository =
                        directoriesAfterRepository.substring(
                                0,
                                directoriesAfterRepository.indexOf(
                                        classContainer.getClassName() + classContainer.getLang().getExtension()
                                )
                        );

                String newDir = config.yate.outputDirectory + directoriesAfterRepository;

                File targetDir = new File(newDir);
                File targetFile = new File(targetDir, destinationName);

                // Ensure target directory exists. If not, create the missing directories
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                // Move the file
                Files.move(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                return;
            } catch (Exception e) {
                YateConsole.INSTANCE.error("Exception thrown when moving file to directory");
                e.printStackTrace();
            }
        }
    }
}
