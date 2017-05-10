package space.exploration.mars.rover.bootstrap;

import java.io.IOException;

import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.Priority;

import space.exploration.mars.rover.kernel.Rover;

public class MarsMissionLaunch {

	public static void main(String[] args) {
		configureLogging();
		try {
			new Rover(MatrixCreation.getMatrixConfig(), MatrixCreation.getComsConfig());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static void configureLogging() {
		FileAppender fa = new FileAppender();
		fa.setFile("roverStatusReports/roverStatus_" + Long.toString(System.currentTimeMillis()) + ".txt");
		fa.setLayout(new PatternLayout("%-4r [%t] %-5p %c %x - %m%n"));
		fa.setThreshold(Level.toLevel(Priority.INFO_INT));
		fa.activateOptions();
		org.apache.log4j.Logger.getRootLogger().addAppender(fa);
	}

}
