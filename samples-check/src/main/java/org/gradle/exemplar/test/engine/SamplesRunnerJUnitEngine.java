package org.gradle.exemplar.test.engine;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.exemplar.executor.CliCommandExecutor;
import org.gradle.exemplar.executor.CommandExecutionResult;
import org.gradle.exemplar.executor.CommandExecutor;
import org.gradle.exemplar.executor.ExecutionMetadata;
import org.gradle.exemplar.loader.SamplesDiscovery;
import org.gradle.exemplar.model.Command;
import org.gradle.exemplar.model.Sample;
import org.gradle.exemplar.test.normalizer.OutputNormalizer;
import org.gradle.exemplar.test.runner.SampleModifier;
import org.gradle.exemplar.test.runner.SamplesRunner;
import org.gradle.exemplar.test.verifier.AnyOrderLineSegmentedOutputVerifier;
import org.gradle.exemplar.test.verifier.StrictOrderLineSegmentedOutputVerifier;
import org.junit.Assert;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

public class SamplesRunnerJUnitEngine implements TestEngine {

    private final File tempDir;
    private final List<? extends OutputNormalizer> normalizers = emptyList();
    private final List<SampleModifier> sampleModifiers = emptyList();

    public SamplesRunnerJUnitEngine() throws IOException {
        tempDir = Files.createTempDirectory("samples-junit-engine").toFile();
    }

    @Override
    public String getId() {
        return "SamplesRunnerJUnitEngine";
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        EngineDescriptor engineDescriptor = new EngineDescriptor(uniqueId, "Samples Runner JUnit Engine");
        for (Sample sample : SamplesDiscovery.externalSamples(new File("src/docs/snippets"))) {
            engineDescriptor.addChild(new SampleTestDescriptor(sample));
        }
        return engineDescriptor;
    }

    @Override
    public void execute(ExecutionRequest request) {
        TestDescriptor engineDescriptor = request.getRootTestDescriptor();
        EngineExecutionListener listener = request.getEngineExecutionListener();

        listener.executionStarted(engineDescriptor);
        for (TestDescriptor testDescriptor : engineDescriptor.getChildren()) {
            SampleTestDescriptor descriptor = (SampleTestDescriptor) testDescriptor;
            listener.executionStarted(testDescriptor);

            try {
                runSample(descriptor.sample);
                listener.executionFinished(testDescriptor, TestExecutionResult.successful());
            } catch (Throwable t) {
                listener.executionFinished(testDescriptor, TestExecutionResult.failed(t));
            }

        }
        listener.executionFinished(engineDescriptor, TestExecutionResult.successful());
    }

    private void runSample(Sample sample) throws Exception {
        final Sample testSpecificSample = initSample(sample);
        File baseWorkingDir = testSpecificSample.getProjectDir();

        // Execute and verify each command
        for (Command command : testSpecificSample.getCommands()) {
            File workingDir = baseWorkingDir;

            if (command.getExecutionSubdirectory() != null) {
                workingDir = new File(workingDir, command.getExecutionSubdirectory());
            }

            // This should be some kind of plugable executor rather than hard-coded here
            if (command.getExecutable().equals("cd")) {
                baseWorkingDir = new File(baseWorkingDir, command.getArgs().get(0)).getCanonicalFile();
                continue;
            }

            CommandExecutionResult result = executeSample(getExecutionMetadata(testSpecificSample.getProjectDir()), workingDir, command);

            if (result.getExitCode() != 0 && !command.isExpectFailure()) {
                Assert.fail(String.format("Expected sample invocation to succeed but it failed.%nCommand was: '%s %s'%nWorking directory: '%s'%n[BEGIN OUTPUT]%n%s%n[END OUTPUT]%n", command.getExecutable(), StringUtils.join(command.getArgs(), " "), workingDir.getAbsolutePath(), result.getOutput()));
            } else if (result.getExitCode() == 0 && command.isExpectFailure()) {
                Assert.fail(String.format("Expected sample invocation to fail but it succeeded.%nCommand was: '%s %s'%nWorking directory: '%s'%n[BEGIN OUTPUT]%n%s%n[END OUTPUT]%n", command.getExecutable(), StringUtils.join(command.getArgs(), " "), workingDir.getAbsolutePath(), result.getOutput()));
            }

            verifyOutput(command, result);
        }
    }

    private void verifyOutput(final Command command, final CommandExecutionResult executionResult) {
        if (command.getExpectedOutput() == null) {
            return;
        }

        String expectedOutput = command.getExpectedOutput();
        String actualOutput = executionResult.getOutput();

        for (OutputNormalizer normalizer : normalizers) {
            actualOutput = normalizer.normalize(actualOutput, executionResult.getExecutionMetadata());
        }

        if (command.isAllowDisorderedOutput()) {
            new AnyOrderLineSegmentedOutputVerifier().verify(expectedOutput, actualOutput, command.isAllowAdditionalOutput());
        } else {
            new StrictOrderLineSegmentedOutputVerifier().verify(expectedOutput, actualOutput, command.isAllowAdditionalOutput());
        }
    }

    private CommandExecutionResult executeSample(ExecutionMetadata executionMetadata, File workingDir, Command command) {
        return selectExecutor(executionMetadata, workingDir, command).execute(command, executionMetadata);
    }

    protected CommandExecutor selectExecutor(ExecutionMetadata executionMetadata, File workingDir, Command command) {
        boolean expectFailure = command.isExpectFailure();
        if (command.getExecutable().equals("gradle")) {
            return new PluginUnderTestAwareGradleRunnerCommandExecutor(workingDir, null, expectFailure);
        }
        return new CliCommandExecutor(workingDir);
    }

    private ExecutionMetadata getExecutionMetadata(final File tempSampleOutputDir) {
        Map<String, String> systemProperties = new HashMap<>();
        for (String systemPropertyKey : SamplesRunner.SAFE_SYSTEM_PROPERTIES) {
            systemProperties.put(systemPropertyKey, System.getProperty(systemPropertyKey));
        }

        return new ExecutionMetadata(tempSampleOutputDir, systemProperties);
    }

    private Sample initSample(final Sample sampleIn) throws IOException {
        File tmpProjectDir = new File(tempDir, sampleIn.getId());
        FileUtils.copyDirectory(sampleIn.getProjectDir(), tmpProjectDir);
        Sample sample = new Sample(sampleIn.getId(), tmpProjectDir, sampleIn.getCommands());
        for (SampleModifier sampleModifier : sampleModifiers) {
            sample = sampleModifier.modify(sample);
        }
        return sample;
    }

    private static class SampleTestDescriptor extends AbstractTestDescriptor {

        private final Sample sample;

        private SampleTestDescriptor(Sample sample) {
            super(UniqueId.forEngine("SamplesRunnerJUnitEngine" + sample.getId()), sample.getId());
            this.sample = sample;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }
}