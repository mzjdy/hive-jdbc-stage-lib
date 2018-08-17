/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.hive_jdbc;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.Target;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.lib.cache.CacheCleaner;
import com.streamsets.pipeline.lib.el.ELUtils;
import com.streamsets.pipeline.lib.jdbc.*;
import com.streamsets.pipeline.lib.jdbc.BoneCPPoolConfigBean;
import com.streamsets.pipeline.lib.operation.UnsupportedOperationAction;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import com.jolbox.bonecp.BoneCPDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JDBC Destination for StreamSets Data Collector
 */
public class HiveJdbcTarget extends BaseTarget {
  private static final Logger LOG = LoggerFactory.getLogger(HiveJdbcTarget.class);

  private static final String BONECP_CONFIG_PREFIX = "bonecpConfigBean.";
  private static final String CONNECTION_STRING = BONECP_CONFIG_PREFIX + "connectionString";

  private final boolean rollbackOnError;
  private final boolean useMultiRowOp;
  private final int maxPrepStmtParameters;
  private final int maxPrepStmtCache;

  private final String schema;
  private final String tableNameTemplate;
  private final List<JdbcFieldColumnParamMapping> customMappings;
  private final boolean caseSensitive;

  private final ChangeLogFormat changeLogFormat;
  private final BoneCPPoolConfigBean bonecpConfigBean;
  private final CacheCleaner cacheCleaner;

  private ErrorRecordHandler errorRecordHandler;
  private BoneCPDataSource dataSource = null;
  private ELEval tableNameEval = null;
  private ELVars tableNameVars = null;

  private Connection connection = null;

  private JDBCOperationType defaultOperation;
  private UnsupportedOperationAction unsupportedAction;

  class RecordWriterLoader extends CacheLoader<String, JdbcRecordWriter> {
    @Override
    public JdbcRecordWriter load(String tableName) throws Exception {
      return JdbcRecordReaderWriterFactory.createJdbcRecordWriter(
          bonecpConfigBean.connectionString,
          dataSource,
          schema,
          tableName,
          customMappings,
          rollbackOnError,
          useMultiRowOp,
          maxPrepStmtParameters,
          maxPrepStmtCache,
          defaultOperation,
          unsupportedAction,
          JdbcRecordReaderWriterFactory.createRecordReader(changeLogFormat),
          caseSensitive
      );
    }
  }

  private final LoadingCache<String, JdbcRecordWriter> recordWriters;

  public HiveJdbcTarget(
      final String schema,
      final String tableNameTemplate,
      final List<JdbcFieldColumnParamMapping> customMappings,
      final boolean caseSensitive,
      final boolean rollbackOnError,
      final boolean useMultiRowOp,
      int maxPrepStmtParameters,
      int maxPrepStmtCache,
      final ChangeLogFormat changeLogFormat,
      final JDBCOperationType defaultOperation,
      final UnsupportedOperationAction unsupportedAction,
      final BoneCPPoolConfigBean bonecpConfigBean
  ) {
    this.schema = schema;
    this.tableNameTemplate = tableNameTemplate;
    this.customMappings = customMappings;
    this.caseSensitive = caseSensitive;
    this.rollbackOnError = rollbackOnError;
    this.useMultiRowOp = useMultiRowOp;
    this.maxPrepStmtParameters = maxPrepStmtParameters;
    this.maxPrepStmtCache = maxPrepStmtCache;
    this.changeLogFormat = changeLogFormat;
    this.defaultOperation = defaultOperation;
    this.unsupportedAction = unsupportedAction;
    this.bonecpConfigBean = bonecpConfigBean;

    CacheBuilder cacheBuilder = CacheBuilder.newBuilder()
        .maximumSize(500)
        .expireAfterAccess(1, TimeUnit.HOURS);

    if(LOG.isDebugEnabled()) {
      cacheBuilder.recordStats();
    }

    this.recordWriters = cacheBuilder.build(new RecordWriterLoader());

    cacheCleaner = new CacheCleaner(this.recordWriters, "HiveJdbcTarget", 10 * 60 * 1000);
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());

    Target.Context context = getContext();

    issues = bonecpConfigBean.validateConfigs(context, issues);

    tableNameVars = getContext().createELVars();
    tableNameEval = context.createELEval(JdbcUtil.TABLE_NAME);
    ELUtils.validateExpression(
        tableNameEval,
        tableNameVars,
        tableNameTemplate,
        getContext(),
        Groups.JDBC.getLabel(),
        JdbcUtil.TABLE_NAME,
        JdbcErrors.JDBC_26,
        String.class,
        issues
    );

    if (issues.isEmpty() && null == dataSource) {
      try {
        String tableName = tableNameTemplate;

        dataSource = JdbcUtil.createDataSourceForWrite(
                bonecpConfigBean, schema,
            tableName,
            caseSensitive,
            issues,
            customMappings,
            getContext()
        );
      } catch (RuntimeException | SQLException | StageException e) {
        LOG.debug("Could not connect to data source", e);
        issues.add(getContext().createConfigIssue(Groups.JDBC.name(), CONNECTION_STRING, JdbcErrors.JDBC_00, e.toString()));
      }
    }

    return issues;
  }

  @Override
  public void destroy() {
    JdbcUtil.closeQuietly(connection);

    if (null != dataSource) {
      dataSource.close();
    }
    super.destroy();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void write(Batch batch) throws StageException {
    if (!batch.getRecords().hasNext()) {
      // No records - take the opportunity to clean up the cache so that we don't hold on to memory indefinitely
      cacheCleaner.periodicCleanUp();
    }
    // hive_jdbc target always commit batch execution
    final boolean perRecord = false;
    JdbcUtil.write(batch, schema, tableNameEval, tableNameVars, tableNameTemplate, caseSensitive, recordWriters, errorRecordHandler, perRecord);
  }
}
