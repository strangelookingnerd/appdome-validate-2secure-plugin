package io.jenkins.plugins.appdome.validate.to.secure;

import static io.jenkins.plugins.appdome.validate.to.secure.AppdomeValidateConstants.*;
import static io.jenkins.plugins.appdome.validate.to.secure.Utils.*;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class AppdomeValidator extends Builder implements SimpleBuildStep {
    private final Secret token;
    private String appPath;
    private String outputLocation;

    @DataBoundConstructor
    public AppdomeValidator(Secret token, String appPath) {
        this.token = token;
        this.appPath = appPath;
    }

    /**
     * Clones the Appdome API repository.
     * https://github.com/Appdome/appdome-api-bash.git
     *
     * @param listener         the TaskListener to use for logging
     * @param appdomeWorkspace the working directory of the build
     * @param launcher         used to launch commands.
     * @return the exit code of the process
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if the process is interrupted
     */
    private int CloneAppdomeApi(TaskListener listener, FilePath appdomeWorkspace, Launcher launcher)
            throws IOException, InterruptedException {
        listener.getLogger().println("Updating Appdome Engine...");

        ArgumentListBuilder gitCloneCommand = new ArgumentListBuilder(
                "git", "clone", "https://github.com/Appdome/appdome-api-bash.git");
        return launcher.launch()
                .cmds(gitCloneCommand)
                .pwd(appdomeWorkspace)
                .quiet(true)
                .join();
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        int exitCode;
        FilePath appdomeWorkspace = workspace.createTempDir("AppdomeValidation", "Validate");
        exitCode = CloneAppdomeApi(listener, appdomeWorkspace, launcher);
        if (exitCode == 0) {
            listener.getLogger().println("Appdome engine updated successfully");
            try {
                ExecuteAppdomeApi(run, listener, appdomeWorkspace, workspace, env, launcher);
            } catch (Exception e) {
                listener.error("Couldn't run Appdome Verification, read logs for more information. error:" + e);
                run.setResult(Result.FAILURE);
            }
        } else {
            listener.error("Couldn't Update Appdome engine, read logs for more information.");
            run.setResult(Result.FAILURE);
        }
        deleteAppdomeWorkspacce(listener, appdomeWorkspace);
    }

    private void ExecuteAppdomeApi(
            Run<?, ?> run,
            TaskListener listener,
            FilePath appdomeWorkspace,
            FilePath agentWorkspace,
            EnvVars env,
            Launcher launcher)
            throws IOException, InterruptedException {
        FilePath scriptPath = appdomeWorkspace.child("appdome-api-bash");
        String command = ComposeAppdomeValidateCommand(appdomeWorkspace, agentWorkspace, env, launcher, listener);
        if (command.equals("")) {
            run.setResult(Result.FAILURE);
            return;
        }
        List<String> filteredCommandList =
                Stream.of(command.split(" ")).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        env.put(APPDOME_HEADER_ENV_NAME, APPDOME_BUILDE2SECURE_VERSION);
        listener.getLogger().println("Launching Appdome Validator");
        ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();

        int exitCode = launcher.launch()
                .cmds(filteredCommandList)
                .pwd(scriptPath)
                .envs(env)
                .stdout(stdoutStream)
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .quiet(true)
                .start()
                .join();

        if (exitCode == 0) {
            Boolean isSignedCorrectly = false;
            // Initialize the result to FAILURE
            Result result = Result.FAILURE;

            // Iterate through the log entries once
            for (String logEntry : run.getLog(Integer.MAX_VALUE)) {
                if (logEntry.contains("This app is not built by Appdome")) {
                    // If "This app is not built by Appdome" is found, set the result to UNSTABLE
                    result = Result.UNSTABLE;
                    break; // Exit the loop since we found an unstable condition
                } else if (logEntry.contains("This app is signed correctly")) {
                    // If "This app is signed correctly" is found, continue the loop
                    isSignedCorrectly = true;
                    continue;
                }
            }
            if (isSignedCorrectly && result != Result.UNSTABLE) {
                result = Result.SUCCESS;
            }
            // Set the result based on the conditions
            run.setResult(result);
        } else {
            listener.error("Couldn't run Appdome Verification, exitcode " + exitCode
                    + ".\nCouldn't run Appdome Verification, read logs for more information.");
            run.setResult(Result.FAILURE);
        }
    }

    private String ComposeAppdomeValidateCommand(
            FilePath appdomeWorkspace, FilePath agentWorkspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        StringBuilder command = new StringBuilder("./appdome_api_bash/validate.sh");
        command.append(KEY_FLAG).append(this.getToken());
        String appPath = "";
        // concatenate the app path if it is not empty:
        if (!(Util.fixEmptyAndTrim(this.appPath) == null)) {
            appPath = DownloadFilesOrContinue(this.appPath, appdomeWorkspace, launcher);
        } else {
            appPath = DownloadFilesOrContinue(
                    UseEnvironmentVariable(
                            env, APP_PATH, appPath, APP_FLAG.trim().substring(2)),
                    appdomeWorkspace,
                    launcher);
        }

        if (appPath.isEmpty()) {
            throw new RuntimeException("App path was not provided.");
        } else {
            File file = new File(appPath);
            if (file.exists()) {
                command.append(APP_FLAG).append(appPath);
                listener.getLogger().println("Validating app " + new File(appPath).getName());

            } else {
                listener.fatalError("App file " + appPath + " does not exist");
                return "";
            }
        }

        if (!(Util.fixEmptyAndTrim(this.outputLocation) == null)) {

            if (this.outputLocation.toLowerCase().endsWith(".json")) {
                command.append(OUTPUT_FLAG).append(this.outputLocation);
            } else if (this.outputLocation.endsWith("/")) {
                ArgumentListBuilder args = new ArgumentListBuilder("mkdir", this.outputLocation);
                launcher.launch().cmds(args).pwd(agentWorkspace).quiet(true).join();
                command.append(OUTPUT_FLAG)
                        .append(this.outputLocation)
                        .append(File.separator)
                        .append(VALIDATION_RESULTS_NAME);
            } else {
                listener.error("Output location is not valid. Result won't be save to a JSON file.");
            }
        } else {
            String outputLocationMissing = "";
            if (!isHttpUrl(this.appPath)) {
                outputLocationMissing =
                        (appPath.substring(0, appPath.lastIndexOf("/") + 1)).concat(VALIDATION_RESULTS_NAME);
                command.append(OUTPUT_FLAG).append(outputLocationMissing);
                listener.getLogger()
                        .println("WARNING: The output location for the JSON result was not provided. "
                                + "The JSON data will be saved to " + outputLocationMissing);

            } else {
                listener.getLogger().println("ERROR: Result won't be save to a JSON file.");
            }
        }

        return command.toString();
    }

    public String getToken() {
        return token.getPlainText();
    }

    public String getAppPath() {
        return appPath;
    }

    public String getOutputLocation() {
        return outputLocation;
    }

    @DataBoundSetter
    public void setOutputLocation(String outputLocation) {
        this.outputLocation = outputLocation;
    }

    @Symbol("AppdomeValidator")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @POST
        public FormValidation doCheckToken(@QueryParameter Secret token) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (token != null && Util.fixEmptyAndTrim(token.getPlainText()) == null) {
                return FormValidation.error("Token is required");
            } else if (token != null && token.getPlainText().contains(" ")) {
                return FormValidation.error("White spaces are not allowed in Token.");
            }
            // Perform any additional validation here
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckAppPath(@QueryParameter String appPath) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (appPath != null && Util.fixEmptyAndTrim(appPath) == null) {
                return FormValidation.warning("Application path was not provided.\n "
                        + "Or please ensure that a valid path is provided for application in the environment variable"
                        + " named "
                        + APP_PATH + ".");
            } else if (appPath != null && appPath.contains(" ")) {
                return FormValidation.error("White spaces are not allowed in the path.");
            } else if (appPath != null
                    && !(appPath.endsWith(".aab") || appPath.endsWith(".apk") || (appPath.endsWith(".ipa")))) {
                return FormValidation.error(
                        "Application - File extension is not allowed,"
                                + " allowed extensions are: '.apk' or '.aab'. Please rename your file or upload a different file.");
            }
            // Perform any additional validation here
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckOutputLocation(
                @QueryParameter String outputLocation, @QueryParameter String appPath) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (outputLocation != null && Util.fixEmptyAndTrim(outputLocation) == null) {
                if ((appPath != null && Util.fixEmptyAndTrim(appPath) != null) && !isHttpUrl(appPath)) {
                    String outputLocationMissing =
                            (appPath.substring(0, appPath.lastIndexOf("/") + 1)).concat(VALIDATION_RESULTS_NAME);
                    return FormValidation.warning("Output path for JSON file was not provided. and it will be saved to "
                            + outputLocationMissing);
                } else {
                    return FormValidation.warning("Output path for JSON file was not provided.");
                }
            } else if (outputLocation != null && outputLocation.contains(" ")) {
                return FormValidation.error("White spaces are not allowed in the path.");
            } else if (outputLocation != null && outputLocation.endsWith("/")) {
                return FormValidation.ok(
                        "Output JSON result file will be saved to " + outputLocation + VALIDATION_RESULTS_NAME);
            } else if (outputLocation != null
                    && Util.fixEmptyAndTrim(outputLocation) != null
                    && outputLocation.endsWith(".json")) {
                return FormValidation.ok("JSON result file will be saved to " + outputLocation);
            } else if (outputLocation != null && Util.fixEmptyAndTrim(outputLocation) != null) {
                return FormValidation.error("Please provide a valid path to JSON file results.");
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Appdome Validate-2secure";
        }
    }
}
