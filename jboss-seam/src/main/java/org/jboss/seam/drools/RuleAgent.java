package org.jboss.seam.drools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Properties;
import java.util.Timer;

import org.drools.compiler.compiler.DroolsParserException;
import org.drools.compiler.compiler.PackageBuilder;
import org.drools.core.RuleBaseConfiguration;
import org.drools.core.RuleBaseFactory;
import org.drools.core.RuntimeDroolsException;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.Unwrap;
import org.jboss.seam.annotations.intercept.BypassInterceptors;
import org.jboss.seam.log.LogProvider;
import org.jboss.seam.log.Logging;
import org.jboss.seam.util.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager component for a rule base loaded from a drools RulesAgent
 */
@Scope(ScopeType.APPLICATION)
@BypassInterceptors
public class RuleAgent {
	public static class RuleBaseAgent {

		/**
		 * Following are property keys to be used in the property config file.
		 */
		public static final String NEW_INSTANCE = "newInstance";
		public static final String POLL_INTERVAL = "poll";
		public static final String CONFIG_NAME = "name"; // name is optional
		public static final String USER_NAME = "username";
		public static final String PASSWORD = "password";
		public static final String ENABLE_BASIC_AUTHENTICATION = "enableBasicAuthentication";

		// this is needed for cold starting when BRMS is down (ie only for URL).
		public static final String LOCAL_URL_CACHE = "localCacheDir";

		protected static transient Logger logger = LoggerFactory.getLogger(RuleAgent.class);

		String name;

		/**
		 * This is true if the rulebase is created anew each time.
		 */
		private boolean newInstance;

		/**
		 * The rule base that is being managed.
		 */
		private org.drools.core.RuleBase ruleBase;

		/**
		 * the configuration for the RuleBase
		 */
		private RuleBaseConfiguration ruleBaseConf;

		/**
		 * The timer that is used to monitor for changes and deal with them.
		 */
		private Timer timer;

		/**
		 * Properties configured to load up packages into a rulebase (and monitor them
		 * for changes).
		 */
		public static RuleBaseAgent newRuleAgent(Properties config) {
			return newRuleAgent(config, null);
		}

		public static RuleBaseAgent newRuleAgent(Properties config, RuleBaseConfiguration ruleBaseConf) {
			RuleBaseAgent agent = new RuleBaseAgent(ruleBaseConf);
			if (ruleBaseConf == null) {
				agent.init(config, true);
			} else {
				agent.init(config);
			}
			return agent;
		}

		void init(Properties config) {
			init(config, false);
		}

		/**
		 * 
		 * @param config
		 * @param lookForRuleBaseConfigurations
		 *            true if config contains rule base configuration data that should
		 *            be used.
		 */
		void init(Properties config, boolean lookForRuleBaseConfigurations) {
			boolean newInstance = Boolean.valueOf(config.getProperty(NEW_INSTANCE, "false")).booleanValue();
			int secondsToRefresh = Integer.parseInt(config.getProperty(POLL_INTERVAL, "-1"));
			Properties droolsProperties = new Properties();
			for (Iterator<?> iter = config.keySet().iterator(); iter.hasNext();) {
				String key = (String) iter.next();
				if (ruleBaseConf != null && key.startsWith("drools.")) {
					droolsProperties.setProperty(key, config.getProperty(key));
				}
			}
			if (lookForRuleBaseConfigurations && !droolsProperties.isEmpty()) {
				ruleBaseConf = new RuleBaseConfiguration(droolsProperties);
			}
			configure(newInstance, config, secondsToRefresh);
		}

		public static RuleBaseAgent newRuleAgent(String propsFileName) {
			return newRuleAgent(loadFromProperties(propsFileName));
		}

		public static RuleBaseAgent newRuleAgent(String propsFileName, RuleBaseConfiguration ruleBaseConfiguration) {
			return newRuleAgent(loadFromProperties(propsFileName), ruleBaseConfiguration);
		}

		public void setName(String name) {
			this.name = name;
		}

		static Properties loadFromProperties(String propsFileName) {
			InputStream in = RuleAgent.class.getResourceAsStream(propsFileName);
			Properties props = new Properties();
			try {
				props.load(in);
				return props;
			} catch (IOException e) {
				throw new RuntimeDroolsException(
						"Unable to load properties. Needs to be the path and name of a config file on your classpath.",
						e);
			} finally {
				if (null != in) {
					try {
						in.close();
					} catch (IOException e) {
						throw new RuntimeDroolsException("Unable to load properties. Could not close InputStream.", e);
					}
				}
			}
		}

		synchronized void configure(boolean newInstance, Properties config, int secondsToRefresh) {
			this.newInstance = newInstance;
			if (this.ruleBase == null) {
				ruleBase = RuleBaseFactory.newRuleBase(this.ruleBaseConf);
			}
			try {
				this.ruleBase.addPackage(loadPackage((String) config.getProperty("file")));
			} catch (final Exception e) {
			}
		}

		private static org.drools.core.rule.Package loadPackage(String drl) throws IOException {
			FileInputStream fin = new FileInputStream(new File(drl));
			PackageBuilder b = new PackageBuilder();
			try {
				b.addPackageFromDrl(new InputStreamReader(fin));
				fin.close();
				if (b.hasErrors()) {
					throw new RuntimeDroolsException("Error building rules from source: " + b.getErrors());
				}
				return b.getPackage();
			} catch (DroolsParserException e) {
				throw new RuntimeException(e);
			}
		}

		public synchronized org.drools.core.RuleBase getRuleBase() {
			return this.ruleBase;
		}

		RuleBaseAgent(RuleBaseConfiguration ruleBaseConf) {
			if (ruleBaseConf == null) {
				this.ruleBaseConf = RuleBaseConfiguration.getDefaultInstance();
			} else {
				this.ruleBaseConf = ruleBaseConf;
			}
		}

		boolean isNewInstance() {
			return newInstance;
		}

		public synchronized boolean isPolling() {
			return this.timer != null;
		}

		RuleBaseConfiguration getRuleBaseConfiguration() {
			return ruleBaseConf;
		}
	}

	private static final LogProvider log = Logging.getLogProvider(RuleAgent.class);

	private RuleBaseAgent agent;
	private String configurationFile;

	private String newInstance;
	private String files;
	private String url;
	private String localCacheDir;
	private String poll;
	private String configName;

	@Create
	public void createAgent() throws Exception {
		Properties properties = new Properties();

		loadFromPath(properties, configurationFile);
		setLocalProperties(properties);

		agent = RuleBaseAgent.newRuleAgent(properties);
		log.debug("Creating new rules agent");
	}

	protected void setLocalProperties(Properties properties) {
		if (newInstance != null) {
			properties.setProperty(RuleBaseAgent.NEW_INSTANCE, newInstance);
		}
		if (localCacheDir != null) {
			properties.setProperty(RuleBaseAgent.LOCAL_URL_CACHE, localCacheDir);
		}
		if (configName != null) {
			properties.setProperty(RuleBaseAgent.CONFIG_NAME, configName);
		}
	}

	protected void loadFromPath(Properties properties, String configurationFile) throws IOException {
		if (configurationFile != null) {
			InputStream inputStream = Resources.getResourceAsStream(configurationFile, null);
			if (inputStream != null) {
				try {
					properties.load(inputStream);
				} finally {
					inputStream.close();
				}
			}
		}
	}

	@Unwrap
	public org.drools.core.RuleBase getRuleBase() {
		return agent.getRuleBase();
	}

	public String getNewInstance() {
		return newInstance;
	}

	public void setNewInstance(String newInstance) {
		this.newInstance = newInstance;
	}

	public String getFiles() {
		return files;
	}

	public void setFiles(String files) {
		this.files = files;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getLocalCacheDir() {
		return localCacheDir;
	}

	public void setLocalCacheDir(String localCacheDir) {
		this.localCacheDir = localCacheDir;
	}

	public String getPoll() {
		return poll;
	}

	public void setPoll(String poll) {
		this.poll = poll;
	}

	public String getConfigName() {
		return configName;
	}

	public void setConfigName(String name) {
		this.configName = name;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	public void setConfigurationFile(String brmsConfig) {
		this.configurationFile = brmsConfig;
	}

}
