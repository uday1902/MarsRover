package space.exploration.mars.rover.bootstrap;

import communications.protocol.KafkaConfig;
import org.apache.log4j.*;
import space.exploration.mars.rover.kernel.Rover;

import java.io.IOException;
import java.util.Properties;

public class MarsMissionLaunch {
    public static void main(String[] args) {
        String logFilePath = "";
        try {
            if (args.length == 0) {
                logFilePath = configureLogging(false);
                new Rover(MatrixCreation.getConfig(), KafkaConfig.getKafkaConfig("Rover"), MatrixCreation
                        .getRoverDBConfig(), MatrixCreation.getConfigFilePath());
            } else {
                logFilePath = configureLogging(false);
                Properties marsConfig         = MatrixCreation.convertToPropertyFiles(args[0]);
                Properties dbConfig           = MatrixCreation.convertToPropertyFiles(args[1]);
                String     camCacheLocation   = args[2];
                String     archiveLocation    = args[3];
                String     marsConfigLocation = args[0];

                new Rover(marsConfig, KafkaConfig.getKafkaConfig("Rover"), dbConfig, camCacheLocation,
                          archiveLocation, marsConfigLocation);
            }
            System.out.println("LogFilePath = " + logFilePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String configureLogging(boolean debug) {
        FileAppender fa = new FileAppender();

        if (!debug) {
            fa.setThreshold(Level.toLevel(Priority.INFO_INT));
            fa.setFile("roverStatusReports/roverStatus_" + Long.toString(System.currentTimeMillis()) + ".log");
        } else {
            fa.setThreshold(Level.toLevel(Priority.DEBUG_INT));
            fa.setFile("analysisLogs/roverStatus_" + Long.toString(System.currentTimeMillis()) + ".log");
        }

        fa.setLayout(new EnhancedPatternLayout("%-6d [%25.35t] %-5p %40.80c - %m%n"));
        fa.activateOptions();
        org.apache.log4j.Logger.getRootLogger().addAppender(fa);
        return fa.getFile();
    }

    @Deprecated
    public static void configureLogging(boolean debug, String className) {
        FileAppender fa = new FileAppender();

        if (!debug) {
            fa.setThreshold(Level.toLevel(Priority.INFO_INT));
            fa.setFile(className + "logStatus_" + Long.toString(System.currentTimeMillis()) + ".log");
        } else {
            fa.setThreshold(Level.toLevel(Priority.DEBUG_INT));
            fa.setFile("analysisLogs/" + className + "logStatus_" + Long.toString(System.currentTimeMillis()) + ".log");
        }

        fa.setLayout(new PatternLayout("%d [%t] %p %c %x - %m%n"));

        fa.activateOptions();
        org.apache.log4j.Logger.getRootLogger().addAppender(fa);
    }
}
