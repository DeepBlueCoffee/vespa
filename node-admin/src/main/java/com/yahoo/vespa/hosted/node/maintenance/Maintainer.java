package com.yahoo.vespa.hosted.node.maintenance;

import io.airlift.airline.Cli;
import io.airlift.airline.Command;
import io.airlift.airline.Help;
import io.airlift.airline.Option;
import io.airlift.airline.ParseArgumentsUnexpectedException;
import io.airlift.airline.ParseOptionMissingException;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * @author valerijf
 */
public class Maintainer {
    public static final String JOB_DELETE_OLD_APP_DATA = "delete-old-app-data";
    public static final String JOB_DELETE_OLD_LOGS = "delete-old-logs";
    public static final String JOB_CLEAN_CORE_DUMPS = "clean-core-dumps";
    public static final String JOB_CLEAN_HOME = "clean-home";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Cli.CliBuilder<Runnable> builder = Cli.<Runnable>builder("maintainer.jar")
                .withDescription("This tool makes it easy to delete old log files and other node-admin app data.")
                .withDefaultCommand(Help.class)
                .withCommands(Help.class, DeleteOldAppDataArguments.class, DeleteOldLogsArguments.class,
                        CleanCoreDumpsArguments.class, CleanHomeArguments.class);

        Cli<Runnable> gitParser = builder.build();
        try {
            gitParser.parse(args).run();
        } catch (ParseArgumentsUnexpectedException | ParseOptionMissingException e) {
            System.err.println(e.getMessage());
            gitParser.parse("help").run();
        }
    }

    @Command(name = JOB_DELETE_OLD_APP_DATA, description = "Deletes all directories and their contents that matches the criteria")
    public static class DeleteOldAppDataArguments implements Runnable {
        @Option(name = {"--path"},
                required = true,
                description = "Path to directory which contains the app data")
        private String path;

        @Option(name = {"--max_age"},
                description = "Maximum age (in seconds) of directory to keep")
        private long maxAge = DeleteOldAppData.DEFAULT_MAX_AGE_IN_SECONDS;

        @Option(name = {"--name"},
                description = "Regex pattern to match against the directory name")
        private String regex;

        @Override
        public void run() {
            DeleteOldAppData.deleteDirectories(path, maxAge, regex);
        }
    }

    @Command(name = JOB_DELETE_OLD_LOGS, description = "Deletes all log files that match the criteria in path")
    public static class DeleteOldLogsArguments implements Runnable {
        @Option(name = {"--path"},
                required = true,
                description = "Path to directory which contains the log data")
        private String path;

        @Option(name = {"--max_age"},
                description = "Maximum age (in seconds) of file to keep")
        private long maxAge = DeleteOldAppData.DEFAULT_MAX_AGE_IN_SECONDS;

        @Option(name = {"--name"},
                description = "Regex pattern to match against the filename")
        private String name;

        @Override
        public void run() {
            DeleteOldAppData.deleteFiles(path, maxAge, name, false);
        }
    }

    @Command(name = JOB_CLEAN_CORE_DUMPS, description = "Clean core dumps")
    public static class CleanCoreDumpsArguments implements Runnable {
        @Override
        public void run() {
            File coreDumpsDir = new File("/home/y/var/crash");

            if (coreDumpsDir.exists()) {
                DeleteOldAppData.deleteFilesExceptNMostRecent(coreDumpsDir.getAbsolutePath(), 1);
            }
        }
    }

    @Command(name = JOB_CLEAN_HOME, description = "Clean home directories for large files")
    public static class CleanHomeArguments implements Runnable {
        @Override
        public void run() {
            List<String> exceptions = Arrays.asList("docker", "y", "yahoo");
            File homeDir = new File("/home/");
            long MB = 1 << 20;

            if (homeDir.exists() && homeDir.isDirectory()) {
                for (File file : homeDir.listFiles()) {
                    if (! exceptions.contains(file.getName())) {
                        DeleteOldAppData.deleteFilesLargerThan(file, 100*MB);
                    }
                }
            }
        }
    }
}
