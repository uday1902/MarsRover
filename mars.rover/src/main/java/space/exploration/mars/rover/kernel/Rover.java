package space.exploration.mars.rover.kernel;

import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import communications.protocol.ModuleDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.exploration.communications.protocol.InstructionPayloadOuterClass.InstructionPayload.TargetPackage;
import space.exploration.communications.protocol.communication.RoverStatusOuterClass;
import space.exploration.communications.protocol.service.WeatherQueryOuterClass;
import space.exploration.mars.rover.communication.Radio;
import space.exploration.mars.rover.diagnostics.Pacemaker;
import space.exploration.mars.rover.diagnostics.RoverGarbageCollector;
import space.exploration.mars.rover.diagnostics.SleepMonitor;
import space.exploration.mars.rover.environment.EnvironmentUtils;
import space.exploration.mars.rover.environment.MarsArchitect;
import space.exploration.mars.rover.environment.RoverCell;
import space.exploration.mars.rover.navigation.NavigationEngine;
import space.exploration.mars.rover.power.Battery;
import space.exploration.mars.rover.power.BatteryMonitor;
import space.exploration.mars.rover.sensor.*;
import space.exploration.mars.rover.utils.RoverUtil;

import java.awt.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Rover {
    public static final String ROVER_NAME        = "Curiosity";
    public static final String PROPULSION_CHOICE = "mars.rover.propulsion.engine.choice";

    protected String marsConfigLocation = null;
    volatile  State  state              = null;

    /* Kernel definition */
    State listeningState;
    State sensingState;
    State movingState;
    State exploringState;
    State transmittingState;
    State hibernatingState;
    State photoGraphingState;
    State radarScanningState;
    State sleepingState;
    State weatherSensingState;
    State sclkBeepingState;
    State danSensingState;

    /* Status messages */
    private long    creationTime = 0l;
    private boolean equipmentEOL = false;

    /* Logging Details */
    private Connection logDBConnection  = null;
    private ResultSet  resultSet        = null;
    private ResultSet  errorSet         = null;
    private Logger     logger           = null;
    private String     dbUserName       = null;
    private String     dbPassword       = null;
    private Statement  statement        = null;
    private Statement  errorStatement   = null;
    private boolean    dbLoggingEnabled = false;

    /* Configuration */
    private volatile MarsArchitect marsArchitect            = null;
    private          Properties    marsConfig               = null;
    private          Properties    comsConfig               = null;
    private          Properties    logDBConfig              = null;
    private          String        cameraImageCacheLocation = null;
    private          String        dataArchiveLocation      = null;
    private          Point         location                 = null;
    private          String        nasaApiAuthKey           = null;

    /* Equipment Stack */
    private volatile Radio            radio            = null;
    private          Lidar            lidar            = null;
    private          ApxsSpectrometer apxsSpectrometer = null;
    private          DANSpectrometer  danSpectrometer  = null;
    private          Camera           camera           = null;
    private          Radar            radar            = null;
    private volatile WeatherSensor    weatherSensor    = null;
    private          NavigationEngine navigationEngine = null;

    /* Kernel Sensors   */
    private volatile SpacecraftClock spacecraftClock = null;
    private volatile PositionSensor  positionSensor  = null;

    /* Contingency Stack */
    private          Map<Point, RoverCell> previousRovers       = null;
    private volatile List<byte[]>          instructionQueue     = null;
    private volatile long                  inRechargingModeTime = 0l;
    private volatile long                  timeMessageReceived  = 0l;
    private volatile boolean               gracefulShutdown     = false;

    /* Resource Management Stack */
    private volatile Semaphore             accessLock            = new Semaphore(1);
    private volatile Pacemaker             pacemaker             = null;
    private volatile Battery               battery               = null;
    private volatile BatteryMonitor        batteryMonitor        = null;
    private volatile SleepMonitor          sleepMonitor          = null;
    private volatile RoverGarbageCollector roverGarbageCollector = null;

    /* Metrics Configuration */
    private final MetricsRegistry metrics               = new MetricsRegistry();
    private       Gauge<Integer>  batteryGauge          = null;
    private       Gauge<Integer>  instructionQueueGauge = null;

    public Rover(Properties marsConfig, Properties comsConfig, Properties logsDBConfig, String marsConfigLocation) {
        this.marsConfig = marsConfig;
        this.comsConfig = comsConfig;
        this.logDBConfig = logsDBConfig;
        this.marsConfigLocation = marsConfigLocation;
        this.dataArchiveLocation = "/dataArchives";
        this.nasaApiAuthKey = "DEMO_KEY";
        bootUp();
    }

    public Rover(Properties marsConfig, Properties comsConfig, Properties logsDBConfig, String
            cameraImageCacheLocation, String dataArchiveLocation, String marsConfigLocation) {
        this.marsConfig = marsConfig;
        this.comsConfig = comsConfig;
        this.logDBConfig = logsDBConfig;
        this.cameraImageCacheLocation = cameraImageCacheLocation;
        this.dataArchiveLocation = dataArchiveLocation;
        this.marsConfigLocation = marsConfigLocation;
        bootUp();
    }

    public static long getOneSolDuration() {
        /* Time scaled by a factor of 60. */
        long time = TimeUnit.HOURS.toMillis(24);
        time += TimeUnit.MINUTES.toMillis(39);
        time += TimeUnit.SECONDS.toMillis(35);
        time += 244;
        return time;
    }

    protected void setRoverConfigProperties(Properties marsConfig) {
        this.marsConfig = marsConfig;
    }

    public synchronized void processPendingMessageQueue() {
        state = listeningState;
        receiveMessage(instructionQueue.remove(0));
    }

    public String getMarsConfigLocation() {
        return marsConfigLocation;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public Connection getLogDBConnection() {
        return logDBConnection;
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    public ResultSet getErrorSet() {
        return errorSet;
    }

    public Logger getLogger() {
        return logger;
    }

    public String getDbUserName() {
        return dbUserName;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public Statement getStatement() {
        return statement;
    }

    public Statement getErrorStatement() {
        return errorStatement;
    }

    public boolean isDbLoggingEnabled() {
        return dbLoggingEnabled;
    }

    public Properties getComsConfig() {
        return comsConfig;
    }

    public Properties getLogDBConfig() {
        return logDBConfig;
    }

    public String getCameraImageCacheLocation() {
        return cameraImageCacheLocation;
    }

    public Point getLocation() {
        return location;
    }

    public Map<Point, RoverCell> getPreviousRovers() {
        return previousRovers;
    }

    public Semaphore getAccessLock() {
        return accessLock;
    }

    public RoverGarbageCollector getRoverGarbageCollector() {
        return roverGarbageCollector;
    }

    public void setMarsConfigLocation(String marsConfigLocation) {
        this.marsConfigLocation = marsConfigLocation;
    }

    public void setListeningState(State listeningState) {
        this.listeningState = listeningState;
    }

    public void setSensingState(State sensingState) {
        this.sensingState = sensingState;
    }

    public void setMovingState(State movingState) {
        this.movingState = movingState;
    }

    public void setExploringState(State exploringState) {
        this.exploringState = exploringState;
    }

    public void setTransmittingState(State transmittingState) {
        this.transmittingState = transmittingState;
    }

    public void setHibernatingState(State hibernatingState) {
        this.hibernatingState = hibernatingState;
    }

    public void setPhotoGraphingState(State photoGraphingState) {
        this.photoGraphingState = photoGraphingState;
    }

    public void setRadarScanningState(State radarScanningState) {
        this.radarScanningState = radarScanningState;
    }

    public void setSleepingState(State sleepingState) {
        this.sleepingState = sleepingState;
    }

    public void setSclkBeepingState(State sclkBeepingState) {
        this.sclkBeepingState = sclkBeepingState;
    }

    public void setDanSensingState(State danSensingState) {
        this.danSensingState = danSensingState;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public void setLogDBConnection(Connection logDBConnection) {
        this.logDBConnection = logDBConnection;
    }

    public void setResultSet(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    public void setErrorSet(ResultSet errorSet) {
        this.errorSet = errorSet;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public void setDbUserName(String dbUserName) {
        this.dbUserName = dbUserName;
    }

    public void setDbPassword(String dbPassword) {
        this.dbPassword = dbPassword;
    }

    public void setStatement(Statement statement) {
        this.statement = statement;
    }

    public void setErrorStatement(Statement errorStatement) {
        this.errorStatement = errorStatement;
    }

    public void setDbLoggingEnabled(boolean dbLoggingEnabled) {
        this.dbLoggingEnabled = dbLoggingEnabled;
    }

    public void setMarsArchitect(MarsArchitect marsArchitect) {
        this.marsArchitect = marsArchitect;
    }

    public void setMarsConfig(Properties marsConfig) {
        this.marsConfig = marsConfig;
    }

    public void setComsConfig(Properties comsConfig) {
        this.comsConfig = comsConfig;
    }

    public void setLogDBConfig(Properties logDBConfig) {
        this.logDBConfig = logDBConfig;
    }

    public void setCameraImageCacheLocation(String cameraImageCacheLocation) {
        this.cameraImageCacheLocation = cameraImageCacheLocation;
    }

    public void setDataArchiveLocation(String dataArchiveLocation) {
        this.dataArchiveLocation = dataArchiveLocation;
    }

    public void setLocation(Point location) {
        this.location = location;
    }

    public void setNasaApiAuthKey(String nasaApiAuthKey) {
        this.nasaApiAuthKey = nasaApiAuthKey;
    }

    public void setRadio(Radio radio) {
        this.radio = radio;
    }

    public void setLidar(Lidar lidar) {
        this.lidar = lidar;
    }

    public void setApxsSpectrometer(ApxsSpectrometer apxsSpectrometer) {
        this.apxsSpectrometer = apxsSpectrometer;
    }

    public void setDanSpectrometer(DANSpectrometer danSpectrometer) {
        this.danSpectrometer = danSpectrometer;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public void setRadar(Radar radar) {
        this.radar = radar;
    }

    public void setWeatherSensor(WeatherSensor weatherSensor) {
        this.weatherSensor = weatherSensor;
    }

    public void setNavigationEngine(NavigationEngine navigationEngine) {
        this.navigationEngine = navigationEngine;
    }

    public void setInstructionQueue(List<byte[]> instructionQueue) {
        this.instructionQueue = instructionQueue;
    }

    public void setAccessLock(Semaphore accessLock) {
        this.accessLock = accessLock;
    }

    public void setPacemaker(Pacemaker pacemaker) {
        this.pacemaker = pacemaker;
    }

    public void setBattery(Battery battery) {
        this.battery = battery;
    }

    public void setBatteryMonitor(BatteryMonitor batteryMonitor) {
        this.batteryMonitor = batteryMonitor;
    }

    public void setSleepMonitor(SleepMonitor sleepMonitor) {
        this.sleepMonitor = sleepMonitor;
    }

    public void setBatteryGauge(Gauge<Integer> batteryGauge) {
        this.batteryGauge = batteryGauge;
    }

    public void setInstructionQueueGauge(Gauge<Integer> instructionQueueGauge) {
        this.instructionQueueGauge = instructionQueueGauge;
    }

    public boolean isGracefulShutdown() {
        return gracefulShutdown;
    }

    public synchronized void setGracefulShutdown(boolean gracefulShutdown) {
        this.gracefulShutdown = gracefulShutdown;
    }

    public MetricsRegistry getMetrics() {
        return metrics;
    }

    public synchronized void acquireAceessLock(String acquiringParty) throws InterruptedException {
        accessLock.acquire();
        logger.debug("Rover accessLock acquired by " + acquiringParty);
        logger.debug(" Rover's current state = " + getState().getStateName());
    }

    public synchronized void releaseAccessLock(String releasingParty) {
        accessLock.release();
        logger.debug("Rover accessLock released by " + releasingParty);
        logger.debug(" Rover's current state = " + getState().getStateName());
    }

    public synchronized long getInRechargingModeTime() {
        return inRechargingModeTime;
    }

    public synchronized void setInRechargingModeTime(long inRechargingModeTime) {
        this.inRechargingModeTime = inRechargingModeTime;
    }

    public synchronized void shootNeutrons() {
        state.shootNeutrons();
    }

    public String getNasaApiAuthKey() {
        return nasaApiAuthKey;
    }

    public long getTimeMessageReceived() {
        return timeMessageReceived;
    }

    public void setTimeMessageReceived(long timeMessageReceived) {
        this.timeMessageReceived = timeMessageReceived;
    }

    public String getDataArchiveLocation() {
        return dataArchiveLocation;
    }

    public synchronized State getListeningState() {
        return listeningState;
    }

    public synchronized State getTransmittingState() {
        return transmittingState;
    }

    public synchronized State getSleepingState() {
        return sleepingState;
    }

    public synchronized State getSclkBeepingState() {
        return sclkBeepingState;
    }

    public synchronized State getHibernatingState() {
        return hibernatingState;
    }

    public synchronized State getDanSensingState() {
        return danSensingState;
    }

    public synchronized NavigationEngine getNavigationEngine() {
        return navigationEngine;
    }

    public synchronized void setPreviousRovers(Map<Point, RoverCell> previousRovers) {
        this.previousRovers = previousRovers;
    }

    public synchronized void senseWeather(WeatherQueryOuterClass.WeatherQuery weatherQuery) {
        state.senseWeather(weatherQuery);
    }

    /**
     * The clock sends this signal to the rover, so it can update all its sensors.
     *
     * @param sol
     */
    public synchronized void updateSensors(int sol) {
        weatherSensor.calibrateREMS(sol);
        danSpectrometer.calibrateDanSensor(sol);
        apxsSpectrometer.calibrateApxsSpectrometer(sol);
    }

    public synchronized Camera getCamera() {
        return camera;
    }

    public synchronized void receiveMessage(byte[] message) {
        state.receiveMessage(message);
    }

    public synchronized void scanSurroundings() {
        state.scanSurroundings();
    }

    public synchronized void move(TargetPackage payload) {
        state.move(payload);
    }

    public synchronized void performRadarScan() {
        state.performRadarScan();
    }

    public synchronized void wakeUp() {
        state.wakeUp();
    }

    public synchronized void sleep() {
        state.sleep();
    }

    public synchronized void getSclkInformation() {
        state.getSclkInformation();
    }

    public synchronized void exploreArea() {
        state.exploreArea();
    }

    public synchronized void transmitMessage(byte[] message) {
        state.transmitMessage(message);
    }

    public synchronized void synchronizeClocks(String utcTime) {
        state.synchronizeClocks(utcTime);
    }

    public synchronized void gracefulShutdown() {
        state.gracefulShutdown();
    }

    public synchronized void authorizeTransmission(ModuleDirectory.Module module, byte[] message) {
        /* Choose to filter upon modules here */
        logger.debug("Module " + module.getValue() + " overriding rover state to authorize transmission. endOfLife " +
                             "set" +
                             " to " + Boolean.toString(equipmentEOL));
        state = transmittingState;
        transmitMessage(message);
    }

    public synchronized void setPaceMaker(Pacemaker pacemaker) {
        this.pacemaker = pacemaker;
    }

    public synchronized Radio getRadio() {
        return radio;
    }

    public synchronized MarsArchitect getMarsArchitect() {
        return marsArchitect;
    }

    public synchronized Properties getMarsConfig() {
        return marsConfig;
    }

    public synchronized List<byte[]> getInstructionQueue() {
        return this.instructionQueue;
    }

    public synchronized Battery getBattery() {
        return battery;
    }

    public synchronized Lidar getLidar() {
        return lidar;
    }

    public synchronized DANSpectrometer getDanSpectrometer() {
        return danSpectrometer;
    }

    public synchronized ApxsSpectrometer getApxsSpectrometer() {
        return apxsSpectrometer;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized void setState(State state) {
        this.state = state;
    }

    public synchronized boolean isEquipmentEOL() {
        return equipmentEOL;
    }

    public synchronized void setEquipmentEOL(boolean equipmentEOL) {
        this.equipmentEOL = equipmentEOL;
    }

    public SpacecraftClock getSpacecraftClock() {
        return spacecraftClock;
    }

    public synchronized void setSpacecraftClock(SpacecraftClock spacecraftClock) {
        this.spacecraftClock.stopClock();
        this.spacecraftClock = spacecraftClock;
        this.spacecraftClock.start();
    }

    public synchronized Pacemaker getPacemaker() {
        return this.pacemaker;
    }

    public synchronized RoverGarbageCollector getGarbageCollector() {
        return this.roverGarbageCollector;
    }

    public synchronized void setGarbageCollector(RoverGarbageCollector garbageCollector) {
        this.roverGarbageCollector = garbageCollector;
    }

    public synchronized void setRoverGarbageCollector(RoverGarbageCollector roverGarbageCollector) {
        this.roverGarbageCollector = roverGarbageCollector;
    }

    public synchronized boolean isDiagnosticFriendly() {
        return ((this.state == this.listeningState)
                && (instructionQueue.isEmpty()));
    }

    public synchronized void getNasaApiCredentials() {
        this.nasaApiAuthKey = marsConfig.getProperty("nasa.api.authentication.key");
    }

    public synchronized void configureDB() {
        dbLoggingEnabled = Boolean.parseBoolean(logDBConfig.getProperty("mars.rover.database.logging.enable"));

        if (!dbLoggingEnabled) {
            return;
        }

        try {
            System.out.println("Configuring database");
            dbUserName = logDBConfig.getProperty("mars.rover.database.user");
            dbPassword = logDBConfig.getProperty("mars.rover.database.password");
            logDBConnection = DriverManager
                    .getConnection("jdbc:mysql://" + logDBConfig.getProperty("mars.rover.database.host")
                                           + "/" + logDBConfig.getProperty("mars.rover.database.dbName")
                                           + "?user=" + dbUserName + "&password=" + dbPassword);
            statement = logDBConnection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet
                    .CONCUR_UPDATABLE);
            resultSet = statement.executeQuery("SELECT * FROM " + logDBConfig.getProperty("mars.rover.database" +
                                                                                                  ".logTableName"));
            errorStatement = logDBConnection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet
                    .CONCUR_UPDATABLE);
            errorSet = errorStatement.executeQuery("SELECT * FROM " + logDBConfig.getProperty("mars.rover.database" +
                                                                                                      ".errorTableName"));
        } catch (SQLException sqlException) {
            sqlException.printStackTrace();
        }
    }

    public synchronized void configureSpectrometer(Point origin) {
        int     spectrometerLifeSpan = apxsSpectrometer.getLifeSpan();
        boolean spectrometerEOL      = apxsSpectrometer.isEndOfLife();
        this.apxsSpectrometer = new ApxsSpectrometer(this);
    }

    public synchronized void configureLidar(Point origin, int cellWidth, int range) {
        int     lidarLifespan = lidar.getLifeSpan();
        boolean lidarEOL      = lidar.isEndOfLife();
        this.lidar = new Lidar(origin, cellWidth, range, this);
        lidar.setLifeSpan(lidarLifespan);
        lidar.setEndOfLife(lidarEOL);
    }

    public synchronized void configureRadar() {
        int     radarLifespan = radar.getLifeSpan();
        boolean radarEOL      = radar.isEndOfLife();
        this.radar = new Radar(this);
        radar.setLifeSpan(radarLifespan);
        radar.setEndOfLife(radarEOL);
    }

    /**
     * Note: Battery charging capacity diminishes with usage.
     *
     * @param recharged
     */
    public synchronized void configureBattery(boolean recharged) {
        int lifeSpan = 0;

        if (this.battery != null) {
            lifeSpan = battery.getLifeSpan();
        }

        this.battery = new Battery(marsConfig);
        if (recharged) {
            battery.setLifeSpan(lifeSpan - 1);
            int fullBatteryLifeSpan = Integer.parseInt(marsConfig.getProperty("mars.rover.battery.lifeSpan"));
            battery.setPrimaryPowerUnits(battery.getPrimaryPowerUnits() - (fullBatteryLifeSpan - battery.getLifeSpan
                    ()));
        }

        this.batteryMonitor = new BatteryMonitor(this);
        batteryMonitor.monitor();
        RoverUtil.roverSystemLog(logger, "Battery Monitor configured!", "INFO");
    }

    private synchronized void configureRadio() {
        this.radio = new Radio(comsConfig, this);
    }

    public synchronized Radar getRadar() {
        return radar;
    }

    public synchronized void powerCheck(int powerConsumed) {
        battery.setPrimaryPowerUnits(battery.getPrimaryPowerUnits() - powerConsumed);
    }

    public Gauge<Integer> getBatteryGauge() {
        return batteryGauge;
    }

    public Gauge<Integer> getInstructionQueueGauge() {
        return instructionQueueGauge;
    }

    public synchronized void activateCameraById(String camId) {
        state.activateCameraById(camId);
    }

    public synchronized List<IsEquipment> getEquimentList() {
        List<IsEquipment> equipmentList = new ArrayList<>();
        equipmentList.add(this.battery);
        equipmentList.add(this.radio);
        equipmentList.add(this.lidar);
        equipmentList.add(this.camera);
        equipmentList.add(this.apxsSpectrometer);
        equipmentList.add(this.radar);
        equipmentList.add(this.weatherSensor);
        equipmentList.add(this.spacecraftClock);
        equipmentList.add(this.positionSensor);
        equipmentList.add(this.danSpectrometer);
        return equipmentList;
    }

    public synchronized byte[] getBootupMessage() {
        RoverStatusOuterClass.RoverStatus.Location location = RoverStatusOuterClass.RoverStatus.Location.newBuilder()
                .setX(marsArchitect.getRobot().getLocation().x)
                .setY(marsArchitect.getRobot().getLocation().y).build();
        RoverStatusOuterClass.RoverStatus.Builder rBuilder = RoverStatusOuterClass.RoverStatus.newBuilder();
        rBuilder.setModuleReporting(ModuleDirectory.Module.KERNEL.getValue());
        rBuilder.setSCET(System.currentTimeMillis());
        rBuilder.setLocation(location);
        rBuilder.setBatteryLevel(this.getBattery().getPrimaryPowerUnits());
        rBuilder.setNotes("Rover " + ROVER_NAME + " reporting to earth after a restart - How are you earth?");
        return rBuilder.build().toByteArray();
    }

    public synchronized WeatherSensor getWeatherSensor() {
        return weatherSensor;
    }

    public synchronized State getWeatherSensingState() {
        return weatherSensingState;
    }

    public synchronized void setWeatherSensingState(State weatherSensingState) {
        this.weatherSensingState = weatherSensingState;
    }

    public synchronized BatteryMonitor getBatteryMonitor() {
        return batteryMonitor;
    }

    public synchronized SleepMonitor getSleepMonitor() {
        return sleepMonitor;
    }

    public PositionSensor getPositionSensor() {
        return positionSensor;
    }

    public synchronized void setPositionSensor(PositionSensor positionSensor) {
        this.positionSensor = positionSensor;
    }

    private void setUpGauges() {
        batteryGauge = new Gauge<Integer>() {
            @Override
            public Integer value() {
                return battery.getAuxiliaryPowerUnits();
            }
        };

        instructionQueueGauge = new Gauge<Integer>() {
            @Override
            public Integer value() {
                return instructionQueue.size();
            }
        };
    }

    public State getSensingState() {
        return sensingState;
    }

    public State getMovingState() {
        return movingState;
    }

    public State getExploringState() {
        return exploringState;
    }

    public State getPhotoGraphingState() {
        return photoGraphingState;
    }

    public State getRadarScanningState() {
        return radarScanningState;
    }

    protected synchronized void reflectRoverState() {
        marsArchitect.getMarsSurface().setTitle(state.getStateName());
        marsArchitect.getMarsSurface().repaint();
    }

    public synchronized void bootUp() {
        Thread.currentThread().setName("roverMain");
        setUpGauges();
        metrics.newGauge(new MetricName(Rover.class, "RoverBattery"), batteryGauge);
        metrics.newGauge(new MetricName(Rover.class, "RoverInstructionQueue"), instructionQueueGauge);

        this.creationTime = System.currentTimeMillis();
        this.previousRovers = new HashMap<>();
        this.weatherSensor = new WeatherSensor(this);
        this.danSpectrometer = new DANSpectrometer(this);

        this.spacecraftClock = new SpacecraftClock(marsConfig);
        spacecraftClock.setRover(this);
        spacecraftClock.start();

        this.positionSensor = new PositionSensor(marsConfig);
        positionSensor.start();

        this.marsArchitect = new MarsArchitect(marsConfig);
        this.listeningState = new ListeningState(this);
        this.hibernatingState = new HibernatingState(this);
        this.exploringState = new ApxsSensingState(this);
        this.movingState = new MovingState(this);
        this.photoGraphingState = new PhotographingState(this);
        this.sensingState = new LidarSensingState(this);
        this.transmittingState = new TransmittingState(this);
        this.radarScanningState = new RadarScanningState(this);
        this.weatherSensingState = new WeatherSensingState(this);
        this.sleepingState = new SleepingState(this);
        this.sclkBeepingState = new SclkTimingState(this);
        this.danSensingState = new DANSensingState(this);

        this.instructionQueue = new ArrayList<>();
        this.logger = LoggerFactory.getLogger(Rover.class);
        RoverUtil.roverSystemLog(logger, "Rover + " + ROVER_NAME + " states initialized. ", "INFO ");

        this.pacemaker = new Pacemaker(this);
        pacemaker.pulse();
        RoverUtil.roverSystemLog(logger, "Pacemaker initialized. ", "INFO ");

        sleepMonitor = new SleepMonitor(this);
        RoverUtil.roverSystemLog(logger, "SleepMonitor initialized. ", "INFO ");

        roverGarbageCollector = new RoverGarbageCollector(this);
        roverGarbageCollector.start();
        RoverUtil.roverSystemLog(logger, "RoverGC initialized. ", "INFO ");

        String[] stPosition = marsConfig.getProperty(EnvironmentUtils.ROBOT_START_LOCATION).split(",");
        location = new Point(Integer.parseInt(stPosition[0]), Integer.parseInt(stPosition[1]));
        RoverUtil.roverSystemLog(logger, "Rover current position is = " + location.toString(), "INFO");

        int cellWidth = Integer.parseInt(marsConfig.getProperty(EnvironmentUtils.CELL_WIDTH_PROPERTY));

        this.lidar = new Lidar(location, cellWidth, cellWidth, this);
        lidar.setLifeSpan(Integer.parseInt(marsConfig.getProperty(Lidar.LIFESPAN)));

        this.apxsSpectrometer = new ApxsSpectrometer(this);
        this.camera =
                (cameraImageCacheLocation == null) ? new Camera(this.marsConfig, this) : new Camera(this.marsConfig,
                                                                                                    this,
                                                                                                    cameraImageCacheLocation);
        this.radar = new Radar(this);

        this.navigationEngine = new NavigationEngine(this.getMarsConfig());

        configureDB();
        getNasaApiCredentials();
        configureBattery(false);
        configureRadio();
        configureRadar();

        state = transmittingState;
        transmitMessage(getBootupMessage());
    }

    protected void shutdownRover() {
        logger.info("Stopping all daemon processes.");

        logger.info(" 1. Stopping Pacemaker.");
        pacemaker.hardInterrupt();
        pacemaker = null;

        logger.info(" 2. Stopping GarbageCollector.");
        roverGarbageCollector.hardInterrupt();
        roverGarbageCollector = null;

        logger.info(" 3. Stopping BatteryMonitor. ");
        batteryMonitor.hardInterrupt();
        batteryMonitor = null;

        logger.info(" 4. Stopping SleepMonitor. ");
        sleepMonitor.hardInterrupt();
        sleepMonitor = null;

        logger.info(" 5. Stopping PositionSensor.");
        positionSensor.hardInterrupt();
        positionSensor = null;

        logger.info("6. Saving properties file. ");

        String utcTime = spacecraftClock.getUTCTime();
        marsConfig.replace(SpacecraftClock.SCLK_START_TIME, utcTime);
        try {
            logger.info("Writing properties file to :: " + marsConfigLocation);
            RoverUtil.saveOffProperties(marsConfig, marsConfigLocation);
        } catch (IOException e) {
            logger.error("Encountered an exception when writing properties file during shutdown.", e);
        }

        logger.info(" 7. Stopping SpacecraftClock. ");
        spacecraftClock.hardInterrupt();
        spacecraftClock = null;

        logger.info(" 8. Shutting down graphics.");
        marsArchitect.getMarsSurface().dispose();
        marsArchitect = null;

        logger.info(" 9. Stopping Radio Communications.");
        radio.stopRadio();
        radio = null;

        logger.info("Houston this is Curiosity saying goodbye! Hope I did OK!");
        Runtime.getRuntime().halt(0);
    }
}
