package azkaban.jobtype;

/*
 * Copyright 2012 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.parse.HiveParser.searchCondition_return;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TaskReport;
import org.apache.hadoop.mapred.JobHistory.JobInfo;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.log4j.Logger;
import org.apache.pig.PigRunner;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.MapReduceOper;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.MROperPlan;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.parser.QueryParser.bool_cond_return;
import org.apache.pig.tools.pigstats.JobStats;
import org.apache.pig.tools.pigstats.OutputStats;
import org.apache.pig.tools.pigstats.PigProgressNotificationListener;
import org.apache.pig.tools.pigstats.PigStats;

import azkaban.jobExecutor.ProcessJob;
import azkaban.jobtype.AmbrosePigProgressNotificationListener.JobProgressField;
import azkaban.security.commons.HadoopSecurityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class HadoopSecurePigWrapper {
	
	private static File pigLogFile;
	
	private static Set<String> jobs;
	
	private static boolean securityEnabled;
	
//	private static BasicPigProgressNotificationListener pigProgressListener;
	
	public static void main(final String[] args) throws Exception {
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				try {
					cancelJob();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});
		
		final Logger logger = Logger.getRootLogger();
		
		
		
		String propsFile = System.getenv(ProcessJob.JOB_PROP_ENV);
		Properties prop = new Properties();
		prop.load(new BufferedReader(new FileReader(propsFile)));

		final Configuration conf = new Configuration();
		
		UserGroupInformation.setConfiguration(conf);
		securityEnabled = UserGroupInformation.isSecurityEnabled();
		
		pigLogFile = new File(System.getenv("PIG_LOG_FILE"));
		jobs = new HashSet<String>();
//		pigProgressListener = new BasicPigProgressNotificationListener();
		
		if (shouldProxy(prop)) {
			
			UserGroupInformation proxyUser = null;
			String userToProxy = prop.getProperty("user.to.proxy");
			
			if(securityEnabled) {
				String filelocation = System.getenv(UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION);
				if(filelocation == null) {
					throw new RuntimeException("hadoop token information not set.");
				}		
				if(!new File(filelocation).exists()) {
					throw new RuntimeException("hadoop token file doesn't exist.");			
				}
				
				logger.info("Found token file " + filelocation);
		//		logger.info("Security enabled is " + UserGroupInformation.isSecurityEnabled());
				
				logger.info("Setting " + HadoopSecurityManager.MAPREDUCE_JOB_CREDENTIALS_BINARY + " to " + filelocation);
				System.setProperty(HadoopSecurityManager.MAPREDUCE_JOB_CREDENTIALS_BINARY, filelocation);
				
				UserGroupInformation loginUser = null;
				
		
				//logger.info("Proxying enabled.");
				
					
				loginUser = UserGroupInformation.getLoginUser();
				logger.info("Current logged in user is " + loginUser.getUserName());
				
				
				logger.info("Creating proxy user.");
				proxyUser = UserGroupInformation.createProxyUser(userToProxy, loginUser);
		
				for (Token<?> token: loginUser.getTokens()) {
					proxyUser.addToken(token);
				}
			}
			else {
				proxyUser = UserGroupInformation.createRemoteUser(userToProxy);
			}
			
			_logger.info("Proxied as user " + userToProxy);
			
			proxyUser.doAs(
					new PrivilegedExceptionAction<Void>() {
						@Override
						public Void run() throws Exception {
								runPigJob(args);
								return null;
						}
					});
		}
		else {
			runPigJob(args);
		}
	}
	
	public static void runPigJob(String[] args) throws Exception {
		PigStats stats = PigRunner.run(args, null);
		if (!stats.isSuccessful()) {
			if (pigLogFile != null) {
				handleError(pigLogFile);
			}
			throw new RuntimeException("Pig job failed.");
		}
		else {

		}
	}
	
	private static void cancelJob() throws Exception {
		// doesn't seem needed as the job dies by itself if the process is killed
	}
	
	private static void handleError(File pigLog) throws Exception {
		System.out.println();
		System.out.println("Pig logfile dump:");
		System.out.println();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(pigLog));
			String line = reader.readLine();
			while (line != null) {
				System.err.println(line);
				line = reader.readLine();
			}
			reader.close();
		}
		catch (FileNotFoundException e) {
			System.err.println("pig log file: " + pigLog + "  not found.");
		}
	}
	
	public static boolean shouldProxy(Properties prop) {
		String shouldProxy = prop.getProperty(HadoopSecurityManager.ENABLE_PROXYING);

		return shouldProxy != null && shouldProxy.equals("true");
	}
}

