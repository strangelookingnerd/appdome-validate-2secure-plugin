package com.appdome.validate.to.secure;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.File;
import java.io.IOException;
import java.util.InputMismatchException;

public class Utils {
    public static boolean isHttpUrl(String urlString) {
        String regex = "^https?://.*$";
        return urlString.matches(regex);
    }
    static String UseEnvironmentVariable(EnvVars env, String envName, String fieldValue, String filedName) {
        if (fieldValue == null || fieldValue.isEmpty() && (env.get(envName) != null && !(Util.fixEmptyAndTrim(env.get(envName)) == null))) {
            return env.get(envName, fieldValue);
        }
        throw new InputMismatchException("The field '" + filedName + "' was not provided correctly. " +
                "Kindly ensure that the environment variable '" + envName + "' has been correctly inserted.");
    }

    static String DownloadFilesOrContinue(String paths, FilePath agentWorkspace, Launcher launcher) throws IOException, InterruptedException {
        ArgumentListBuilder args;
        FilePath userFilesPath;
        StringBuilder pathsToFilesOnAgent = new StringBuilder();
        String[] splitPathFiles = paths.split(",");

        for (String singlePath : splitPathFiles) {
            if (!isHttpUrl(singlePath)) {
                pathsToFilesOnAgent.append(singlePath).append(',');
            } else {
                args = new ArgumentListBuilder("mkdir", "user_files");
                launcher.launch()
                        .cmds(args)
                        .pwd(agentWorkspace)
                        .quiet(true)
                        .join();
                userFilesPath = agentWorkspace.child("user_files");
                pathsToFilesOnAgent.append(DownloadFiles(userFilesPath, launcher, singlePath)).append(',');
            }
        }
        return pathsToFilesOnAgent.substring(0, pathsToFilesOnAgent.length() - 1).trim();
    }
    private static String DownloadFiles(FilePath userFilesPath, Launcher launcher, String url) throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("curl", "-LO", url);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        String outputPath = userFilesPath.getRemote() + File.separator + fileName;
        launcher.launch()
                .cmds(args)
                .pwd(userFilesPath)
                .quiet(true)
                .join();
        return outputPath;
    }


    /**
     * This method deletes the contents and the workspace directory of an Appdome workspace.
     *
     * @param listener         listener object to log messages
     * @param appdomeWorkspace the path to the Appdome workspace to delete
     * @throws IOException          : if there is an error accessing the file system
     * @throws InterruptedException if the current thread is interrupted by another thread while
     *                              it is waiting for the workspace deletion to complete.
     */
    static void deleteAppdomeWorkspacce(TaskListener listener, FilePath appdomeWorkspace) throws
            IOException, InterruptedException {
        listener
                .getLogger()
                .print("Deleting temporary files." + System.lineSeparator());
        appdomeWorkspace.deleteSuffixesRecursive();
        appdomeWorkspace.deleteContents();
        appdomeWorkspace.deleteRecursive();
    }
}
