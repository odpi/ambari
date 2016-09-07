/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.view.huetoambarimigration.datasource.queryset.ambariqueryset.hive.savedqueryset;

public class OracleQuerySetAmbariDB extends QuerySetAmbariDB {

  @Override
  protected String getSqlMaxDSidFromTableIdSavedQuery(int id) {
    return "select MAX(cast(ds_id as integer)) as max from ds_savedquery_" + id + "";
  }

  @Override
  protected String getTableIdSqlFromInstanceNameSavedQuery() {
    return "select id from viewentity where class_name LIKE 'org.apache.ambari.view.hive.resources.savedQueries.SavedQuery' and view_instance_name=?";
  }

  @Override
  protected String getSqlMaxDSidFromTableIdHistoryQuery(int id) {
    return "select MAX(cast(ds_id as integer)) as max from ds_jobimpl_" + id + "";
  }
  @Override
  protected String getTableIdSqlFromInstanceNameHistoryQuery() {
    return "select id from viewentity where class_name LIKE 'org.apache.ambari.view.hive.resources.jobs.viewJobs.JobImpl' and view_instance_name=?";
  }

  @Override
  protected String getSqlInsertHiveHistory(int id) {
    return "INSERT INTO ds_jobimpl_" + id + " values (?,'','','','','default',?,0,'','',?,?,?,'','job','','','Unknown',?,'','Worksheet')";
  }
  @Override
  protected String getSqlInsertSavedQuery(int id) {
    return "INSERT INTO ds_savedquery_" + id + " values (?,?,?,?,?,?)";
  }

  @Override
  protected String getRevSqlSavedQuery(int id, String maxcount) {
    return "delete from  ds_savedquery_" + id + " where ds_id='" + maxcount + "'";
  }
  @Override
  protected String getRevSqlHistoryQuery(int id, String maxcount) {
    return "delete from  ds_jobimpl_" + id + " where ds_id='" + maxcount + "'";
  }
  @Override
  protected String getSqlSequenceNoFromAmbariSequence(int id) {
    return "select sequence_value from ambari_sequences where sequence_name ='ds_savedquery_"+id+"_id_seq'";
  }
  @Override
  protected String getSqlUpdateSequenceNo(int id) {
    return "update ambari_sequences set sequence_value=? where sequence_name='ds_savedquery_"+id+"_id_seq'";
  }
}
