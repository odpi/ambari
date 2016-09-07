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

package org.apache.ambari.logsearch.manager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.ambari.logsearch.common.LogSearchConstants;
import org.apache.ambari.logsearch.common.MessageEnums;
import org.apache.ambari.logsearch.common.SearchCriteria;
import org.apache.ambari.logsearch.dao.UserConfigSolrDao;
import org.apache.ambari.logsearch.query.QueryGeneration;
import org.apache.ambari.logsearch.util.RESTErrorUtil;
import org.apache.ambari.logsearch.util.SolrUtil;
import org.apache.ambari.logsearch.util.StringUtil;
import org.apache.ambari.logsearch.view.VLogfeederFilterWrapper;
import org.apache.ambari.logsearch.view.VUserConfig;
import org.apache.ambari.logsearch.view.VUserConfigList;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField.Count;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UserConfigMgr extends MgrBase {

  static Logger logger = Logger.getLogger(UserConfigMgr.class);

  @Autowired
  UserConfigSolrDao userConfigSolrDao;

  @Autowired
  SolrUtil solrUtil;

  @Autowired
  RESTErrorUtil restErrorUtil;

  @Autowired
  QueryGeneration queryGenerator;

  @Autowired
  StringUtil stringUtil;

  public String saveUserConfig(VUserConfig vHistory) {

    SolrInputDocument solrInputDoc = new SolrInputDocument();
    if (!isValid(vHistory)) {
      throw restErrorUtil.createRESTException("No FilterName Specified",
          MessageEnums.INVALID_INPUT_DATA);
    }

    if (isNotUnique(vHistory) && !vHistory.isOverwrite()) {
      throw restErrorUtil.createRESTException(
          "Name '" + vHistory.getFiltername() + "' already exists",
          MessageEnums.INVALID_INPUT_DATA);
    }
    String loggedInUserName = vHistory.getUserName();
    String filterName = vHistory.getFiltername();

    solrInputDoc.addField(LogSearchConstants.ID, vHistory.getId());
    solrInputDoc.addField(LogSearchConstants.USER_NAME, loggedInUserName);
    solrInputDoc.addField(LogSearchConstants.VALUES, vHistory.getValues());
    solrInputDoc.addField(LogSearchConstants.FILTER_NAME, filterName);
    solrInputDoc.addField(LogSearchConstants.ROW_TYPE, vHistory.getRowType());
    List<String> shareNameList = vHistory.getShareNameList();
    if (shareNameList != null && !shareNameList.isEmpty()) {
      solrInputDoc.addField(LogSearchConstants.SHARE_NAME_LIST, shareNameList);
    }
    // Check whether the Filter Name exists in solr
    SolrQuery solrQuery = new SolrQuery();
    queryGenerator.setMainQuery(solrQuery, null);
    queryGenerator.setSingleIncludeFilter(solrQuery,
        LogSearchConstants.FILTER_NAME, solrUtil.makeSearcableString(filterName));
    queryGenerator.setSingleIncludeFilter(solrQuery,
        LogSearchConstants.USER_NAME, loggedInUserName);
    try {
      QueryResponse queryResponse = userConfigSolrDao.process(solrQuery);
      if (queryResponse != null) {
        SolrDocumentList documentList = queryResponse.getResults();
        if (documentList != null && !documentList.isEmpty()
            && !vHistory.isOverwrite()) {
          logger.error("Filtername is already present");
          throw restErrorUtil.createRESTException(
              "Filtername is already present", MessageEnums.INVALID_INPUT_DATA);
        }
      }
    } catch (SolrException | SolrServerException | IOException e) {
      logger.error("Error in checking same filtername config", e);
      throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
          .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
    }

    try {
      userConfigSolrDao.addDocs(solrInputDoc);
      return convertObjToString(solrInputDoc);
    } catch (SolrException | SolrServerException | IOException e) {
      logger.error("Error saving user config. solrDoc=" + solrInputDoc, e);
      throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
          .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
    }
  }

  private boolean isNotUnique(VUserConfig vHistory) {
    String filterName = vHistory.getFiltername();
    String rowType = vHistory.getRowType();

    if (filterName != null && rowType != null) {
      SolrQuery solrQuery = new SolrQuery();
      filterName = solrUtil.makeSearcableString(filterName);
      solrQuery.setQuery(LogSearchConstants.COMPOSITE_KEY + ":" + filterName
          + "-" + rowType);
      queryGenerator.setRowCount(solrQuery, 0);
      try {
        Long numFound = userConfigSolrDao.process(solrQuery).getResults()
            .getNumFound();
        if (numFound > 0) {
          return true;
        }
      } catch (SolrException | SolrServerException | IOException e) {
        logger.error("Error while checking if userConfig is unique.", e);
      }
    }
    return false;
  }

  private boolean isValid(VUserConfig vHistory) {
    return !stringUtil.isEmpty(vHistory.getFiltername())
        && !stringUtil.isEmpty(vHistory.getRowType())
        && !stringUtil.isEmpty(vHistory.getUserName())
        && !stringUtil.isEmpty(vHistory.getValues());
  }

  public void deleteUserConfig(String id) {
    try {
      userConfigSolrDao.deleteUserConfig(id);
    } catch (SolrException | SolrServerException | IOException e) {
      throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
          .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
    }
  }

  @SuppressWarnings("unchecked")
  public String getUserConfig(SearchCriteria searchCriteria) {

    SolrDocumentList solrList = new SolrDocumentList();
    VUserConfigList userConfigList = new VUserConfigList();

    String rowType = (String) searchCriteria
        .getParamValue(LogSearchConstants.ROW_TYPE);
    if (stringUtil.isEmpty(rowType)) {
      throw restErrorUtil.createRESTException("row type was not specified",
          MessageEnums.INVALID_INPUT_DATA);
    }

    String userName = (String) searchCriteria
        .getParamValue(LogSearchConstants.USER_NAME);
    if (stringUtil.isEmpty(userName)) {
      throw restErrorUtil.createRESTException("user name was not specified",
          MessageEnums.INVALID_INPUT_DATA);
    }
    String filterName = (String) searchCriteria
        .getParamValue(LogSearchConstants.FILTER_NAME);
    filterName = stringUtil.isEmpty(filterName) ? "*" : "*" + filterName + "*";

    try {

      SolrQuery userConfigQuery = new SolrQuery();
      queryGenerator.setMainQuery(userConfigQuery, null);
      queryGenerator.setPagination(userConfigQuery, searchCriteria);
      queryGenerator.setSingleIncludeFilter(userConfigQuery,
          LogSearchConstants.ROW_TYPE, rowType);
      queryGenerator.setSingleORFilter(userConfigQuery,
          LogSearchConstants.USER_NAME, userName,
          LogSearchConstants.SHARE_NAME_LIST, userName);
      queryGenerator.setSingleIncludeFilter(userConfigQuery,
          LogSearchConstants.FILTER_NAME, solrUtil.makeSearcableString(filterName));

      if (stringUtil.isEmpty(searchCriteria.getSortBy())) {
        searchCriteria.setSortBy(LogSearchConstants.FILTER_NAME);
      }
      if (stringUtil.isEmpty(searchCriteria.getSortType())) {
        searchCriteria.setSortType("" + SolrQuery.ORDER.asc);
      }

      queryGenerator.setSingleSortOrder(userConfigQuery, searchCriteria);
      solrList = userConfigSolrDao.process(userConfigQuery).getResults();

      Collection<VUserConfig> configList = new ArrayList<VUserConfig>();

      for (SolrDocument solrDoc : solrList) {
        VUserConfig userConfig = new VUserConfig();
        userConfig.setFiltername(""
            + solrDoc.get(LogSearchConstants.FILTER_NAME));
        userConfig.setId("" + solrDoc.get(LogSearchConstants.ID));
        userConfig.setValues("" + solrDoc.get(LogSearchConstants.VALUES));
        userConfig.setRowType("" + solrDoc.get(LogSearchConstants.ROW_TYPE));
        try {
          List<String> shareNameList = (List<String>) solrDoc
              .get(LogSearchConstants.SHARE_NAME_LIST);
          userConfig.setShareNameList(shareNameList);
        } catch (Exception e) {
          // do nothing
        }

        userConfig.setUserName("" + solrDoc.get(LogSearchConstants.USER_NAME));

        configList.add(userConfig);
      }

      userConfigList.setName("historyList");
      userConfigList.setUserConfigList(configList);

      userConfigList.setStartIndex(searchCriteria.getStartIndex());
      userConfigList.setPageSize((int) searchCriteria.getMaxRows());

      userConfigList.setTotalCount((long) solrList.getNumFound());
    } catch (SolrException | SolrServerException | IOException e) {
      // do nothing
      logger.error(e);
      throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
          .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
    }

    return convertObjToString(userConfigList);

  }

  public String updateUserConfig(VUserConfig vuserConfig) {
    return saveUserConfig(vuserConfig);
  }

  // ////////////////////////////LEVEL
  // FILTER/////////////////////////////////////

  /**
   * @return
   */
  public String getUserFilter() {
    VLogfeederFilterWrapper userFilter;
    try {
      userFilter = userConfigSolrDao.getUserFilter();
    } catch (SolrServerException | IOException e) {
      logger.error(e);
      throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
          .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
    }
    return convertObjToString(userFilter);
  }

  /**
   * Creating filter for logfeeder
   *
   * @param String
   * @return
   */
  public String saveUserFiter(String json) {
    if (!stringUtil.isEmpty(json)) {
      VLogfeederFilterWrapper logfeederFilterWrapper = (VLogfeederFilterWrapper) jsonUtil
          .jsonToObj(json, VLogfeederFilterWrapper.class);
      try {
        if (logfeederFilterWrapper == null) {
          logger.error(json + " is a invalid json");
        }
        userConfigSolrDao.saveUserFiter(logfeederFilterWrapper);
      } catch (SolrException | SolrServerException | IOException e) {
        logger.error("user config not able to save", e);
        throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
            .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
      }
    }
    return getUserFilter();
  }

  public String getAllUserName() {
    List<String> userList = new ArrayList<String>();
    try {
      SolrQuery userListQuery = new SolrQuery();
      queryGenerator.setMainQuery(userListQuery, null);
      queryGenerator.setFacetField(userListQuery, LogSearchConstants.USER_NAME);
      QueryResponse queryResponse = userConfigSolrDao.process(userListQuery);
      if (queryResponse == null) {
        return convertObjToString(userList);
      }
      List<Count> counList = queryResponse.getFacetField(
          LogSearchConstants.USER_NAME).getValues();
      for (Count cnt : counList) {
        String userName = cnt.getName();
        userList.add(userName);
      }
    } catch (SolrException | SolrServerException | IOException e) {
      logger.warn("Error getting all users.", e);
      // do nothing
      throw restErrorUtil.createRESTException(MessageEnums.SOLR_ERROR
          .getMessage().getMessage(), MessageEnums.ERROR_SYSTEM);
    }
    return convertObjToString(userList);

  }

}
