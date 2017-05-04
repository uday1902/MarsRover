package space.exploration.mars.rover.environment;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.swing.JFrame;
import javax.swing.JLayeredPane;

import space.exploration.mars.rover.animation.TrackingAnimationEngine;
import space.exploration.mars.rover.sensor.Lidar;
import space.exploration.mars.rover.sensor.Spectrometer;
import space.exploration.mars.rover.animation.AnimationUtil;
import space.exploration.mars.rover.animation.LidarAnimationEngine;
import space.exploration.mars.rover.animation.SpectrometerAnimationEngine;

public class MarsArchitect {
	private Map<Point, SoilComposition>	soilCompositionMap	= null;
	private JFrame						marsSurface			= null;
	private Properties					marsConfig			= null;
	private TrackingAnimationEngine		propulsionEngine	= null;
	private LidarAnimationEngine		lidarEngine			= null;
	private SpectrometerAnimationEngine	spectrometerEngine	= null;
	private Cell						robot				= null;
	private int							cellWidth			= 0;
	private int							robotStepSize		= 0;

	@Deprecated
	public MarsArchitect(Properties matrixDefinition, List<Point> robotPath) {
		this.marsConfig = matrixDefinition;
		this.marsSurface = new JFrame();
		int frameHeight = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.FRAME_HEIGHT_PROPERTY));
		int frameWidth = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.FRAME_WIDTH_PROPERTY));

		marsSurface.setSize(frameWidth, frameHeight);
		marsSurface.setTitle("Matrix");
		this.cellWidth = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.CELL_WIDTH_PROPERTY));
		setUpSurface();
		propulsionEngine = new TrackingAnimationEngine(marsConfig, marsSurface, robotPath, robot);
		propulsionEngine.renderRobotAnimation();
		soilCompositionMap = EnvironmentUtils.setUpSurfaceComposition(marsConfig);
	}

	public MarsArchitect(Properties matrixDefinition) {
		this.marsConfig = matrixDefinition;
		this.marsSurface = new JFrame();
		int frameHeight = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.FRAME_HEIGHT_PROPERTY));
		int frameWidth = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.FRAME_WIDTH_PROPERTY));

		marsSurface.setSize(frameWidth, frameHeight);
		marsSurface.setTitle("Mars");
		this.cellWidth = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.CELL_WIDTH_PROPERTY));
		this.robotStepSize = Integer.parseInt(this.marsConfig.getProperty(EnvironmentUtils.ANIMATION_STEP_SIZE));
		setUpSurface();
		this.propulsionEngine = new TrackingAnimationEngine(marsConfig, marsSurface, robot);
		soilCompositionMap = EnvironmentUtils.setUpSurfaceComposition(marsConfig);
	}

	public void setLidarAnimationEngine(Lidar lidar) {
		this.lidarEngine = new LidarAnimationEngine(marsConfig, robot);
		lidarEngine.setMarsSurface(marsSurface);
		lidarEngine.setLidar(lidar);
	}

	public void setSpectrometerAnimationEngine(Spectrometer spectrometer) {
		this.spectrometerEngine = new SpectrometerAnimationEngine(marsConfig, robot, spectrometer, marsSurface);
	}

	public Map<Point, SoilComposition> getSoilCompositionMap() {
		return this.soilCompositionMap;
	}

	public SpectrometerAnimationEngine getSpectrometerAnimationEngine() {
		return this.spectrometerEngine;
	}

	public SoilComposition getSoilComposition(Point p) {
		return this.soilCompositionMap.get(p);
	}

	public void updateRobotPositions(List<Point> robotPath) {
		propulsionEngine.updateRobotPosition(robotPath);
	}

	public LidarAnimationEngine getLidarAnimationEngine() {
		return lidarEngine;
	}

	public int getCellWidth() {
		return cellWidth;
	}

	public int getRobotStepSize() {
		return robotStepSize;
	}

	private void setUpSurface() {
		JLayeredPane content = AnimationUtil.getContent(marsConfig);

		int roboX = Integer.parseInt(marsConfig.getProperty(EnvironmentUtils.ROBOT_START_LOCATION).split(",")[0]);
		int roboY = Integer.parseInt(marsConfig.getProperty(EnvironmentUtils.ROBOT_START_LOCATION).split(",")[1]);
		this.robot = new Cell(marsConfig);
		AnimationUtil.getRobot(marsConfig, new Point(roboX, roboY), robot);
		content.add(robot, Cell.ROBOT_DEPTH);

		marsSurface.setContentPane(content);
		marsSurface.setVisible(true);
		marsSurface.setName("Mars Surface");
	}
	
	public void returnSurfaceToNormal(){
		AnimationUtil.getStaticEnvironment(this.marsConfig, robot, marsSurface);
	}

	public Cell getRobot() {
		return robot;
	}
}
