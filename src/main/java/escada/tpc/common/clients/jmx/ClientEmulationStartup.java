package escada.tpc.common.clients.jmx;

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import escada.tpc.common.TPCConst;
import escada.tpc.common.args.Arg;
import escada.tpc.common.args.ArgDB;
import escada.tpc.common.args.BooleanArg;
import escada.tpc.common.args.DateArg;
import escada.tpc.common.args.DoubleArg;
import escada.tpc.common.args.IntArg;
import escada.tpc.common.args.StringArg;
import escada.tpc.common.clients.ClientEmulation;
import escada.tpc.common.clients.ClientEmulationMaster;
import escada.tpc.common.database.DatabaseManager;
import escada.tpc.common.resources.DatabaseResources;
import escada.tpc.common.resources.WorkloadResources;
import escada.tpc.common.util.Pad;
import escada.tpc.tpcc.TPCCConst;

public class ClientEmulationStartup implements ClientEmulationStartupMBean,
		ClientEmulationMaster {

	private final static Logger logger = Logger
			.getLogger(ClientEmulationStartup.class);

	private ExecutorService executor = Executors.newCachedThreadPool();

	private DatabaseResources databaseResources;

	private WorkloadResources workloadResources;

	private ServerControl server = new ServerControl();

	private HashMap<String, Integer> replicas = new HashMap<String, Integer>();

	private String tables[];

	private boolean isFailOverEnabled = false;

	public ClientEmulationStartup() throws InvalidTransactionException {
		if (logger.isInfoEnabled()) {
			logger.info("Loading resources!");
		}

		configure();

		databaseResources = new DatabaseResources();
		workloadResources = new WorkloadResources();

		ScheduledExecutorService executor = Executors
				.newSingleThreadScheduledExecutor();

		executor.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				balancing();
			}
		}, 0, 60000, TimeUnit.MILLISECONDS);
	}

	public synchronized void start(String key, String arg, String machine)
			throws InvalidTransactionException {

		if (this.server.getClientStage(key) == null) {

			server.setClientStage(key, Stage.RUNNING);

			String[] args = arg.split("[ ]+");

			this.executor.execute(new Start(key, args, machine));
		} else {
			throw new InvalidTransactionException(key + " start on "
					+ this.server.getClientStage(key));
		}
	}

	public synchronized void start(String key, String args[], String machine)
			throws InvalidTransactionException {
		if (this.server.getClientStage(key) == null) {
			server.setClientStage(key, Stage.RUNNING);
			this.executor.execute(new Start(key, args, machine));
		} else {
			throw new InvalidTransactionException(key + " start on "
					+ this.server.getClientStage(key));
		}
	}

	public synchronized void startScenario(int clients, String scenario)
			throws InvalidTransactionException {
		logger.info("Starting scenario " + scenario);

		StringBuilder str = new StringBuilder();
		String client = server.findFreeClient();
		logger.info("The set of clients is indentifed as " + client);

		String machine = server.findFreeMachine();
		if (machine != null) {
			logger.info("The replica used is " + machine);
			if (configureScenario(clients, scenario, client, machine, 0, str)) {
				start(client, str.toString(), machine);
			} else {
				logger.error("There is something wrong with the parameters");
				throw new InvalidTransactionException(
						"There is something wrong with the parameters");
			}
		} else {
			logger.info("There is no replica avaiable. Please define one.");
			throw new InvalidTransactionException(
					"There is no replica avaiable. Please define one.");
		}

		logger.info("It is done for scenario " + scenario);
	}

	class Start implements Runnable {
		private String[] args;

		private String key;

		private String machine;

		public Start(String key, String[] args, String machine) {
			this.key = key;
			this.args = args;
			this.machine = machine;
		}

		public void run() {
			startClientEmulation(this.key, this.machine, this.args);
		}
	}

	public synchronized void pause(String key)
			throws InvalidTransactionException {
		server.pauseClient(key);
	}

	public synchronized void resume(String key)
			throws InvalidTransactionException {
		server.resumeClient(key);
	}

	public synchronized void stop(String key)
			throws InvalidTransactionException {
		server.stopClient(key);
	}

	public void kill() {
		this.executor.submit(new Kill());
	}

	class Kill implements Runnable {
		public void run() {
			System.exit(0);
		}
	}

	private synchronized void startClientEmulation(String keyArgs,
			String machine, String[] args) {
		ClientEmulation e = null;
		ArgDB db = new ArgDB();

		try {

			StringArg log4jArg = new StringArg("-LOGconfig",
					"Configuration file for Log4J.",
					"% Defines the logging output.", db);

			StringArg ebArg = new StringArg("-EBclass", "EB Factory",
					"% Factory <class> used to create EBs.", db);
			StringArg stArg = new StringArg(
					"-STclass",
					"State Machine for Emulation",
					"% Defines the class used as a state machine for emulation.",
					db);

			StringArg dbArg = new StringArg("-DBclass", "DB Database Class",
					"% String <class> used to instantiate the database.", db);

			StringArg driverArg = new StringArg(
					"-DBdriver",
					"DBDriver Database Class",
					"% String <class> which specifies the driver used to contact the database.",
					db);

			StringArg pathArg = new StringArg(
					"-DBpath",
					"DBpath Database Connection Information",
					"% String which specifies information used to connect to the database.",
					db);
			StringArg usrArg = new StringArg(
					"-DBusr",
					"DBusr Database User",
					"% String <usr> which specifies the user connecting to the database",
					db);

			StringArg passwdArg = new StringArg(
					"-DBpasswd",
					"DBpasswd User password",
					"% String <passwd> which specifies the password correpondent to user connecting to the database.",
					db);

			IntArg poolArg = new IntArg("-POOL", "Connection Pool",
					"% The number of entries available for connection pool...",
					db);

			DateArg st = new DateArg("-ST", "Starting time for ramp-up",
					"% Time (such as Nov 2, 1999 11:30:00 AM CST) "
							+ "at which to start ramp-up."
							+ "  Useful for synchronizing multiple RBEs.",
					System.currentTimeMillis() + 2000L, db);

			IntArg ru = new IntArg("-RU", "Ramp-up time",
					"% Seconds used to warm-up the simulator.", 10 * 60, db);

			IntArg mi = new IntArg("-MI", "Measurement interval",
					"% Minutes used for measuring SUT performance.", 30 * 60,
					db);

			IntArg rd = new IntArg("-RD", "Ramp-down time",
					"% Seconds of steady-state operation following "
							+ "measurment interval.", 5 * 60, db);

			DoubleArg slow = new DoubleArg("-SLOW", "Slow-down factor",
					"% 1000 means one thousand real seconds equals one "
							+ "simulated second.  "
							+ "Accepts factional values and E notation.", 1.0,
					db);

			BooleanArg key = new BooleanArg("-KEY", "Enable thinktime.",
					"% It enables or disables the think time.", true, db);

			IntArg cli = new IntArg("-CLI", "Number of clients",
					"% Number of clients concurrently accessing the database.",
					db);

			StringArg prefix = new StringArg(
					"-PREFIX",
					"Emulation identification and also used as part of the emulation id",
					"% It defines the compositon of the trace file identification and is also used as a component of the "
							+ "emulator id.", db);

			StringArg traceFlag = new StringArg(
					"-TRACEflag",
					"trace files",
					"% It defines the usage of trace file or not (NOTRACE,TRACE,TRACESTRING,TRACETIME)",
					db);

			IntArg fragArg = new IntArg(
					"-FRAG",
					"Shift the clients...",
					"% It shifts the clients in order to access different warehouses...",
					1, db);

			BooleanArg resArg = new BooleanArg(
					"-RESUBMIT",
					"Resubmit Transaction.",
					"% It enables the transaction resubmition when an error occurs.",
					true, db);

			IntArg hostArg = new IntArg("-HOST", "Connection Pool",
					"% The number of entries available for connection pool...",
					0, db);

			if (args.length == 0) {
				Usage(args, db);
				return;
			}

			db.parse(args);

			// DOMConfigurator.configure(log4jArg.s);

			if (!server.isServerHealth(machine)) {
				throw new InvalidTransactionException(
						"Server is not valid. Most likely it is down.");
			}

			logger.info("Starting up the client application.");
			logger.info("Remote Emulator for Database Benchmark ...");
			logger
					.info("Universidade do Minho (Grupo de Sistemas Distribuidos)");
			logger.info("Version 0.1");

			Usage(args, db);

			DatabaseManager dbManager = null;

			Class cl = null;
			Constructor co = null;
			cl = Class.forName(dbArg.s);
			try {
				co = cl.getConstructor(new Class[] { Integer.TYPE });
			} catch (Exception ex) {
			}
			if (co == null) {
				dbManager = (DatabaseManager) cl.newInstance();
			} else {
				dbManager = (DatabaseManager) co
						.newInstance(new Object[] { new Integer(cli.num) });
			}

			dbManager.setConnectionPool(true);
			dbManager.setMaxConnection(poolArg.num);
			dbManager.setDriverName(driverArg.s);
			dbManager.setjdbcPath(pathArg.s);
			dbManager.setUserInfo(usrArg.s, passwdArg.s);

			Vector<ClientEmulation> ebs = new Vector<ClientEmulation>();

			int i = 0;
			for (i = 0; i < cli.num; i++) {

				e = new ClientEmulation();

				e.setFinished(false);
				e.setTraceInformation(prefix.s);
				e.setNumberConcurrentEmulators(cli.num);
				e.setStatusThinkTime(key.flag);
				e.setStatusReSubmit(resArg.flag);
				e.setDatabase(dbManager);
				e.setEmulationName(prefix.s);
				e.setHostId(Integer.toString(hostArg.num));

				e.create(ebArg.s, stArg.s, i, fragArg.num, this, keyArgs);

				Thread t = new Thread(e);
				t.setName(prefix.s + "-" + i);
				e.setThread(t);
				t.start();

				ebs.add(e);
			}

			server.setClientEmulations(keyArgs, ebs);
			server.setClientConfiguration(keyArgs, args);
			server.attachClientToServer(keyArgs, machine);

			logger.info("Running simulation for " + mi.num + " minute(s).");

			waitForRampDown(keyArgs, 0, mi.num);

			for (i = 0; i < cli.num; i++) {
				e = (ClientEmulation) ebs.elementAt(i);
				logger.info("Waiting for the eb " + i + " to finish its job..");
				try {
					e.setCompletion(true);
					e.getThread().join();
				} catch (InterruptedException inte) {
					inte.printStackTrace();
					continue;
				}
			}

			logger.info("EBs finished.");

		} catch (Arg.Exception ae) {
			logger.info("Error:");
			logger.info(ae);
			Usage(args, db);
			return;
		} catch (Exception ex) {
			logger.info("Error while creating clients: ", ex);
		} finally {

			try {
				if (server.getClientStage(keyArgs).equals(Stage.FAILOVER) &&  isFailOverEnabled == true) {
					int cont = 0;
					int contAvailability = 0;
					int load = 0;
					int lowLoad = Integer.MAX_VALUE;
					String lowServer = null;
					if (args != null) {
						while (cont < args.length) {
							if (args[cont].startsWith("jdbc")) {
								break;
							}
							cont++;
						}
						Iterator<String> itServers = server.getServers()
								.iterator();
						String key = null;
						while (itServers.hasNext()) {
							try {
								key = itServers.next();
								verifyingAvailability(key);
								server.setServerHealth(key, true);
								contAvailability++;
								load = server.getNumberOfClientsOnServer(key);
								if (load < lowLoad) {
									lowLoad = load;
									lowServer = key;
								}
							} catch (SQLException ex) {
								ex.printStackTrace();
								server.setServerHealth(key, false);
							}
						}
						if (contAvailability >= 1
								&& args[cont].equals(lowServer)) {
							args[cont] = lowServer;
							start(server.findFreeClient(), args, lowServer);
						}
					}
				}
			}

			catch (Exception exy) {
				exy.printStackTrace();
			}

			this.server.removeClientEmulation(keyArgs);
			this.server.removeClientStage(keyArgs);

			logger.info("Ebs finished their jobs..");
			notifyAll();
		}
	}

	private void Arguments(String args[]) {
		int a;

		for (a = 0; a < args.length; a++) {
			System.out.println("#" + Pad.l(3, "" + (a + 1)) + "  " + args[a]);
		}
	}

	private void Usage(String args[], ArgDB db) {
		logger.info("Input command-line arguments");
		Arguments(args);
		logger.info("Options");
		db.print();
	}

	public synchronized void notifyThreadsCompletion(String key) {
		server.setClientStage(key, Stage.FAILOVER);
		notifyAll();
	}

	private synchronized void waitForRampDown(String key, int start, int term) {
		try {
			if (logger.isInfoEnabled()) {
				logger.info("Start time " + start + " completion time " + term);
			}

			waitForStart(start);

			if (term < 0) {
				return;
			}

			long ini = System.currentTimeMillis();
			long end = 0;
			long remaining = term * 60 * 1000; // TODO: It must be changed to a
			// constant

			while (remaining > 0 && this.server.getClientStage(key) != null
					&& !this.server.getClientStage(key).equals(Stage.STOPPED)
					&& !this.server.getClientStage(key).equals(Stage.FAILOVER)) {

				wait(remaining);

				end = System.currentTimeMillis();

				if (logger.isInfoEnabled()) {
					logger.info("Start remain " + remaining + " ini " + ini
							+ " end " + end);
				}

				remaining = remaining - (end - ini);
				ini = end;

				if (logger.isInfoEnabled()) {
					logger.info("Start remain " + remaining + " ini " + ini
							+ " end " + end);
				}
			}
		} catch (InterruptedException ie) {
			logger.error("In waitforrampdown, caught interrupted exception");
		}
	}

	private void waitForStart(int start) throws InterruptedException {
		if (start < 0) {
			return;
		}

		Thread.sleep(start * 60 * 1000); // TODO - It must be changed to a
		// constant.
	}

	public synchronized DatabaseResources getDatabaseResources() {
		return databaseResources;
	}

	public synchronized void setDatabaseResources(
			DatabaseResources databaseResources) {
		this.databaseResources = databaseResources;
	}

	public synchronized WorkloadResources getWorkloadResources() {
		return workloadResources;
	}

	public synchronized void setWorkloadResources(
			WorkloadResources workloadResources) {
		this.workloadResources = workloadResources;
	}

	public synchronized void addServer(String key)
			throws InvalidTransactionException {
		this.server.addServer(key);
	}

	public synchronized void removeServer(String key)
			throws InvalidTransactionException {
		this.server.removeServer(key);
	}

	public synchronized HashSet<String> getClients()
			throws InvalidTransactionException {
		return (this.server.getClients());
	}

	public synchronized int getNumberOfClients(String key)
			throws InvalidTransactionException {
		return (this.server.getNumberOfClients(key));
	}

	public synchronized int getNumberOfClientsOnServer(String key)
			throws InvalidTransactionException {
		return (this.server.getNumberOfClientsOnServer(key));
	}

	public synchronized HashSet<String> getServers()
			throws InvalidTransactionException {
		return (this.server.getServers());
	}

	public synchronized boolean checkConsistency()
			throws InvalidTransactionException {
		Iterator<String> it = server.getClients().iterator();
		while (it.hasNext()) {
			String key = it.next();
			if (this.server.getClientStage(key) != null
					&& (this.server.getClientStage(key).equals(Stage.RUNNING) || this.server
							.getClientStage(key).equals(Stage.PAUSED))) {

				if (server.getClientEmulations(key) != null) {
					for (ClientEmulation e : server.getClientEmulations(key)) {
						e.stopit();
					}
				}
			}
		}
		System.out.println("------------------ " + server.getClients().size()
				+ " ------------------ ");
		while (server.getClients().size() != 0) {
			try {
				wait();
			} catch (InterruptedException e) {
			}
			System.out.println("------------------ "
					+ server.getClients().size() + " ------------------ ");
		}
		return (checkingConsistency());
	}

	private boolean checkingConsistency() throws InvalidTransactionException {
		boolean ret = true;
		HashSet<String> consistencyBag = new HashSet<String>();
		int cont = 0;

		while (cont < 9 && ret == true) {
			Iterator<String> itServers = server.getServers().iterator();
			while (itServers.hasNext()) {
				String key = itServers.next();
				if (server.isServerHealth(key)) {
					try {
						Connection con = DriverManager.getConnection(key);
						con.setAutoCommit(true);

						String command = "select check_consistency('"
								+ tables[cont] + "','key');";

						System.out.println("command " + command);

						Statement st = con.createStatement();

						ResultSet rs = st.executeQuery(command);

						if (rs.next()) {
							consistencyBag.add(rs.getString(1));
						}
						rs.close();
						st.close();
						con.close();
					} catch (SQLException e) {
						logger.error("Error while connecting to a database", e);
					}
				}
			}
			if (consistencyBag.size() > 1) {
				ret = false;
			}
			consistencyBag.clear();
			cont++;
		}
		return (ret);
	}

	private synchronized void balancing() {
		int contAvailability = 0;
		int load = 0;
		int highLoad = Integer.MIN_VALUE;
		String highServer = null;
		int lowLoad = Integer.MAX_VALUE;
		String lowServer = null;
		try {
			Iterator<String> itServers = server.getServers().iterator();
			String key = null;
			while (itServers.hasNext()) {
				try {
					key = itServers.next();
					verifyingAvailability(key);
					server.setServerHealth(key, true);
					contAvailability++;
					load = server.getNumberOfClientsOnServer(key);
					if (load > highLoad) {
						highLoad = load;
						highServer = key;
					}
					if (load < lowLoad) {
						lowLoad = load;
						lowServer = key;
					}
				} catch (SQLException e) {
					e.printStackTrace();
					server.setServerHealth(key, false);
				}
			}

			if (contAvailability > 1 && isFailOverEnabled == true) {
				int contClient = server.getNumberOfClients("*");
				int mean = (contClient / contAvailability)
						/ TPCConst.getNumMinClients();
				if (mean > 0) {
					String client = server.findServerClient(highServer);
					if (client != null) {
						String args[] = server.getClientConfiguration(client);
						stop(client);
						int cont = 0;
						if (args != null) {
							while (cont < args.length) {
								if (args[cont].startsWith("jdbc")) {
									break;
								}
								cont++;
							}
							args[cont] = lowServer;
							start(server.findFreeClient(), args, lowServer);
						}
					}
				}
			}
		} catch (InvalidTransactionException e) {
			logger.error("Something bad happend !!!", e);
		}
	}

	private void verifyingAvailability(String key) throws SQLException {
		Connection con = DriverManager.getConnection(key);
		con.setAutoCommit(true);
		Statement st = con.createStatement();
		st.executeQuery("select key from warehouse limit 1;");
		st.close();
		con.close();
	}

	private boolean configureScenario(int clients, String scenario, String key,
			String machine, int fixedFrag, StringBuilder str) {
		boolean ret = false;

		if (clients > 0 && scenario.equals("light")) {
			int frag = (fixedFrag == 0 ? replicas.get(machine) : fixedFrag);

			str
					.append("-EBclass escada.tpc.tpcc.TPCCEmulation "
							+ "-LOGconfig configuration.files/logger.xml -KEY true -CLI "
							+ clients
							+ " -STclass escada.tpc.tpcc.TPCCStateTransition "
							+ " -DBclass escada.tpc.tpcc.database.transaction.postgresql.dbPostgresql "
							+ " -TRACEFLAG TRACE -PREFIX "
							+ key
							+ " "
							+ " -DBpath "
							+ machine
							+ " -DBdriver org.postgresql.Driver "
							+ " -DBusr tpcc -DBpasswd tpcc -POOL 20 -MI 2000 -FRAG "
							+ frag + " -RESUBMIT false");

			workloadResources.setNumberOfWarehouses(4);
			TPCConst.setNumMinClients(5);
			TPCCConst.setNumCustomer(100);
			TPCCConst.setNumDistrict(5);
			TPCCConst.setNumItem(10);
			TPCCConst.setNumLastName(99);
			ret = true;
		}

		return (ret);
	}

	private void configure() throws InvalidTransactionException {
		server
				.addServer("jdbc:postgresql://localhost/tpcc?user=alfranio&password=123456");
		server
				.addServer("jdbc:postgresql://192.168.180.32/tpcc?user=tpcc&password=123456");
		server
				.addServer("jdbc:postgresql://192.168.180.33/tpcc?user=tpcc&password=123456");
		server
				.addServer("jdbc:postgresql://192.168.180.34/tpcc?user=tpcc&password=123456");
		server
				.addServer("jdbc:postgresql://192.168.180.35/tpcc?user=tpcc&password=123456");

		replicas
				.put(
						"jdbc:postgresql://localhost/tpcc?user=alfranio&password=123456",
						1);
		replicas
				.put(
						"jdbc:postgresql://192.168.180.32/tpcc?user=tpcc&password=123456",
						1);
		replicas
				.put(
						"jdbc:postgresql://192.168.180.33/tpcc?user=tpcc&password=123456",
						2);
		replicas
				.put(
						"jdbc:postgresql://192.168.180.34/tpcc?user=tpcc&password=123456",
						3);
		replicas
				.put(
						"jdbc:postgresql://192.168.180.35/tpcc?user=tpcc&password=123456",
						4);

		tables = new String[] { "warehouse", "district", "item", "stock",
				"customer", "orders", "order_line", "new_order", "history" };

		try {
			Class.forName("org.postgresql.Driver");
		} catch (java.lang.Exception ex) {

		}
	}
}