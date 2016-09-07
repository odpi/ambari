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

package org.apache.ambari.logfeeder.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

public class FileUtil {
  private static final Logger logger = Logger.getLogger(FileUtil.class);

  public static List<File> getAllFileFromDir(File directory,
      String[] searchFileWithExtensions, boolean checkInSubDir) {
    if (!directory.exists()) {
      logger.error(directory.getAbsolutePath() + " is not exists ");
    } else if (directory.isDirectory()) {
      return (List<File>) FileUtils.listFiles(directory,
          searchFileWithExtensions, checkInSubDir);
    } else {
      logger.error(directory.getAbsolutePath() + " is not Directory ");
    }
    return new ArrayList<File>();
  }
}
