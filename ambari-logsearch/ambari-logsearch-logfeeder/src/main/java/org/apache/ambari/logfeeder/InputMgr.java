/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ambari.logfeeder;

import java.io.EOFException;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.ambari.logfeeder.input.Input;
import org.apache.ambari.logfeeder.input.InputFile;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.log4j.Logger;
import org.apache.solr.common.util.Base64;

public class InputMgr {
  private static final Logger logger = Logger.getLogger(InputMgr.class);

  private List<Input> inputList = new ArrayList<Input>();
  private Set<Input> notReadyList = new HashSet<Input>();

  private boolean isDrain = false;
  private boolean isAnyInputTail = false;

  private String checkPointSubFolderName = "logfeeder_checkpoints";
  private File checkPointFolderFile = null;

  private MetricCount filesCountMetric = new MetricCount();

  private String checkPointExtension = ".cp";
  
  private Thread inputIsReadyMonitor = null;

  public List<Input> getInputList() {
    return inputList;
  }

  public void add(Input input) {
    inputList.add(input);
  }

  public void removeInput(Input input) {
    logger.info("Trying to remove from inputList. "
      + input.getShortDescription());
    Iterator<Input> iter = inputList.iterator();
    while (iter.hasNext()) {
      Input iterInput = iter.next();
      if (iterInput.equals(input)) {
        logger.info("Removing Input from inputList. "
          + input.getShortDescription());
        iter.remove();
      }
    }
  }

  public int getActiveFilesCount() {
    int count = 0;
    for (Input input : inputList) {
      if (input.isReady()) {
        count++;
      }
    }
    return count;
  }

  public void init() {
    filesCountMetric.metricsName = "input.files.count";
    filesCountMetric.isPointInTime = true;

    checkPointExtension = LogFeederUtil.getStringProperty(
      "logfeeder.checkpoint.extension", checkPointExtension);
    for (Input input : inputList) {
      try {
        input.init();
        if (input.isTail()) {
          isAnyInputTail = true;
        }
      } catch (Exception e) {
        logger.error(
          "Error initializing input. "
            + input.getShortDescription(), e);
      }
    }

    if (isAnyInputTail) {
      logger.info("Determining valid checkpoint folder");
      boolean isCheckPointFolderValid = false;
      // We need to keep track of the files we are reading.
      String checkPointFolder = LogFeederUtil
        .getStringProperty("logfeeder.checkpoint.folder");
      if (checkPointFolder != null && !checkPointFolder.isEmpty()) {
        checkPointFolderFile = new File(checkPointFolder);
        isCheckPointFolderValid = verifyCheckPointFolder(checkPointFolderFile);
      }
      if (!isCheckPointFolderValid) {
        // Let's try home folder
        String userHome = LogFeederUtil.getStringProperty("user.home");
        if (userHome != null) {
          checkPointFolderFile = new File(userHome,
            checkPointSubFolderName);
          logger.info("Checking if home folder can be used for checkpoints. Folder="
            + checkPointFolderFile);
          isCheckPointFolderValid = verifyCheckPointFolder(checkPointFolderFile);
        }
      }
      if (!isCheckPointFolderValid) {
        // Let's use tmp folder
        String tmpFolder = LogFeederUtil
          .getStringProperty("java.io.tmpdir");
        if (tmpFolder == null) {
          tmpFolder = "/tmp";
        }
        checkPointFolderFile = new File(tmpFolder,
          checkPointSubFolderName);
        logger.info("Checking if tmps folder can be used for checkpoints. Folder="
          + checkPointFolderFile);
        isCheckPointFolderValid = verifyCheckPointFolder(checkPointFolderFile);
        if (isCheckPointFolderValid) {
          logger.warn("Using tmp folder "
            + checkPointFolderFile
            + " to store check points. This is not recommended."
            + "Please set logfeeder.checkpoint.folder property");
        }
      }

      if (isCheckPointFolderValid) {
        logger.info("Using folder " + checkPointFolderFile
          + " for storing checkpoints");
      }
    }

  }

  public File getCheckPointFolderFile() {
    return checkPointFolderFile;
  }

  private boolean verifyCheckPointFolder(File folderPathFile) {
    if (!folderPathFile.exists()) {
      // Create the folder
      try {
        if (!folderPathFile.mkdir()) {
          logger.warn("Error creating folder for check point. folder="
            + folderPathFile);
        }
      } catch (Throwable t) {
        logger.warn("Error creating folder for check point. folder="
          + folderPathFile, t);
      }
    }

    if (folderPathFile.exists() && folderPathFile.isDirectory()) {
      // Let's check whether we can create a file
      File testFile = new File(folderPathFile, UUID.randomUUID()
        .toString());
      try {
        testFile.createNewFile();
        return testFile.delete();
      } catch (IOException e) {
        logger.warn(
          "Couldn't create test file in "
            + folderPathFile.getAbsolutePath()
            + " for checkPoint", e);
      }
    }
    return false;
  }

  public void monitor() {
    for (Input input : inputList) {
      if (input.isReady()) {
        input.monitor();
      } else {
        if (input.isTail()) {
          logger.info("Adding input to not ready list. Note, it is possible this component is not run on this host. So it might not be an issue. "
            + input.getShortDescription());
          notReadyList.add(input);
        } else {
          logger.info("Input is not ready, so going to ignore it "
            + input.getShortDescription());
        }
      }
    }
    // Start the monitoring thread if any file is in tail mode
    if (isAnyInputTail) {
       inputIsReadyMonitor = new Thread("InputIsReadyMonitor") {
        @Override
        public void run() {
          logger.info("Going to monitor for these missing files: "
            + notReadyList.toString());
          while (true) {
            if (isDrain) {
              logger.info("Exiting missing file monitor.");
              break;
            }
            try {
              Iterator<Input> iter = notReadyList.iterator();
              while (iter.hasNext()) {
                Input input = iter.next();
                try {
                  if (input.isReady()) {
                    input.monitor();
                    iter.remove();
                  }
                } catch (Throwable t) {
                  logger.error("Error while enabling monitoring for input. "
                    + input.getShortDescription());
                }
              }
              Thread.sleep(30 * 1000);
            } catch (Throwable t) {
              // Ignore
            }
          }
        }
      };
      inputIsReadyMonitor.start();
    }
  }

  public void addToNotReady(Input notReadyInput) {
    notReadyList.add(notReadyInput);
  }

  public void addMetricsContainers(List<MetricCount> metricsList) {
    for (Input input : inputList) {
      input.addMetricsContainers(metricsList);
    }
    filesCountMetric.count = getActiveFilesCount();
    metricsList.add(filesCountMetric);
  }

  public void logStats() {
    for (Input input : inputList) {
      input.logStat();
    }

    filesCountMetric.count = getActiveFilesCount();
    LogFeederUtil.logStatForMetric(filesCountMetric,
      "Stat: Files Monitored Count", null);
  }

  public void close() {
    for (Input input : inputList) {
      try {
        input.setDrain(true);
      } catch (Throwable t) {
        logger.error(
          "Error while draining. input="
            + input.getShortDescription(), t);
      }
    }
    isDrain = true;

    // Need to get this value from property
    int iterations = 30;
    int waitTimeMS = 1000;
    int i = 0;
    boolean allClosed = true;
    for (i = 0; i < iterations; i++) {
      allClosed = true;
      for (Input input : inputList) {
        if (!input.isClosed()) {
          try {
            allClosed = false;
            logger.warn("Waiting for input to close. "
              + input.getShortDescription() + ", "
              + (iterations - i) + " more seconds");
            Thread.sleep(waitTimeMS);
          } catch (Throwable t) {
            // Ignore
          }
        }
      }
      if (allClosed) {
        break;
      }
    }
    if (!allClosed) {
      logger.warn("Some inputs were not closed. Iterations=" + i);
      for (Input input : inputList) {
        if (!input.isClosed()) {
          logger.warn("Input not closed. Will ignore it."
            + input.getShortDescription());
        }
      }
    } else {
      logger.info("All inputs are closed. Iterations=" + i);
    }

  }

  public void checkInAll() {
    for (Input input : inputList) {
      input.checkIn();
    }
  }

  public void cleanCheckPointFiles() {

    if (checkPointFolderFile == null) {
      logger.info("Will not clean checkPoint files. checkPointFolderFile="
        + checkPointFolderFile);
      return;
    }
    logger.info("Cleaning checkPoint files. checkPointFolderFile="
      + checkPointFolderFile.getAbsolutePath());
    try {
      // Loop over the check point files and if filePath is not present, then move to closed
      String searchPath = "*" + checkPointExtension;
      FileFilter fileFilter = new WildcardFileFilter(searchPath);
      File[] checkPointFiles = checkPointFolderFile.listFiles(fileFilter);
      int totalCheckFilesDeleted = 0;
      for (File checkPointFile : checkPointFiles) {
        RandomAccessFile checkPointReader = null;
        try {
          checkPointReader = new RandomAccessFile(checkPointFile, "r");

          int contentSize = checkPointReader.readInt();
          byte b[] = new byte[contentSize];
          int readSize = checkPointReader.read(b, 0, contentSize);
          if (readSize != contentSize) {
            logger.error("Couldn't read expected number of bytes from checkpoint file. expected="
              + contentSize
              + ", read="
              + readSize
              + ", checkPointFile=" + checkPointFile);
          } else {
            // Create JSON string
            String jsonCheckPointStr = new String(b, 0, readSize);
            Map<String, Object> jsonCheckPoint = LogFeederUtil
              .toJSONObject(jsonCheckPointStr);

            String logFilePath = (String) jsonCheckPoint
              .get("file_path");
            String logFileKey = (String) jsonCheckPoint
              .get("file_key");
            if (logFilePath != null && logFileKey != null) {
              boolean deleteCheckPointFile = false;
              File logFile = new File(logFilePath);
              if (logFile.exists()) {
                Object fileKeyObj = InputFile
                  .getFileKey(logFile);
                String fileBase64 = Base64
                  .byteArrayToBase64(fileKeyObj
                    .toString().getBytes());
                if (!logFileKey.equals(fileBase64)) {
                  deleteCheckPointFile = true;
                  logger.info("CheckPoint clean: File key has changed. old="
                    + logFileKey
                    + ", new="
                    + fileBase64
                    + ", filePath="
                    + logFilePath
                    + ", checkPointFile="
                    + checkPointFile.getAbsolutePath());
                }
              } else {
                logger.info("CheckPoint clean: Log file doesn't exist. filePath="
                  + logFilePath
                  + ", checkPointFile="
                  + checkPointFile.getAbsolutePath());
                deleteCheckPointFile = true;
              }
              if (deleteCheckPointFile) {
                logger.info("Deleting CheckPoint file="
                  + checkPointFile.getAbsolutePath()
                  + ", logFile=" + logFilePath);
                checkPointFile.delete();
                totalCheckFilesDeleted++;
              }
            }
          }
        } catch (EOFException eof) {
          logger.warn("Caught EOFException. Ignoring reading existing checkPoint file. "
            + checkPointFile);
        } catch (Throwable t) {
          logger.error("Error while checking checkPoint file. "
            + checkPointFile, t);
        } finally {
          if (checkPointReader != null) {
            try {
              checkPointReader.close();
            } catch (Throwable t) {
              logger.error("Error closing checkPoint file. "
                + checkPointFile, t);
            }
          }
        }
      }
      logger.info("Deleted " + totalCheckFilesDeleted
        + " checkPoint file(s). checkPointFolderFile="
        + checkPointFolderFile.getAbsolutePath());

    } catch (Throwable t) {
      logger.error("Error while cleaning checkPointFiles", t);
    }
  }

  public void waitOnAllInputs() {
    //wait on inputs
    if (inputList != null) {
      for (Input input : inputList) {
        if (input != null) {
          Thread inputThread = input.getThread();
          if (inputThread != null) {
            try {
              inputThread.join();
            } catch (InterruptedException e) {
              // ignore
            }
          }
        }
      }
    }
    // wait on monitor
    if (inputIsReadyMonitor != null) {
      try {
        this.close();
        inputIsReadyMonitor.join();
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }
}
