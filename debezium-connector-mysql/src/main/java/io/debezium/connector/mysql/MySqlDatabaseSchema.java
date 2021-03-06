/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.NotThreadSafe;
import io.debezium.connector.AbstractSourceInfo;
import io.debezium.connector.mysql.MySqlSystemVariables.MySqlScope;
import io.debezium.connector.mysql.antlr.MySqlAntlrDdlParser;
import io.debezium.relational.HistorizedRelationalDatabaseSchema;
import io.debezium.relational.RelationalTableFilters;
import io.debezium.relational.SystemVariables;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchemaBuilder;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlChanges;
import io.debezium.relational.ddl.DdlChanges.DatabaseStatementStringConsumer;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.relational.ddl.DdlParserListener.Event;
import io.debezium.relational.ddl.DdlParserListener.TableEvent;
import io.debezium.relational.ddl.DdlParserListener.TableIndexEvent;
import io.debezium.relational.history.DatabaseHistory;
import io.debezium.relational.history.TableChanges;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.schema.TopicSelector;
import io.debezium.text.MultipleParsingExceptions;
import io.debezium.text.ParsingException;
import io.debezium.util.Collect;
import io.debezium.util.SchemaNameAdjuster;

/**
 * Component that records the schema history for databases hosted by a MySQL database server. The schema information includes
 * the {@link Tables table definitions} and the Kafka Connect {@link #schemaFor(TableId) Schema}s for each table, where the
 * {@link Schema} excludes any columns that have been {@link MySqlConnectorConfig#COLUMN_EXCLUDE_LIST specified} in the
 * configuration.
 * <p>
 * The history is changed by {@link #applyDdl(SourceInfo, String, String, DatabaseStatementStringConsumer) applying DDL
 * statements}, and every change is {@link DatabaseHistory persisted} as defined in the supplied {@link MySqlConnectorConfig MySQL
 * connector configuration}. This component can be reconstructed (e.g., on connector restart) and the history
 * {@link #loadHistory(SourceInfo) loaded} from persisted storage.
 * <p>
 * Note that when {@link #applyDdl(SourceInfo, String, String, DatabaseStatementStringConsumer) applying DDL statements}, the
 * caller is able to supply a {@link DatabaseStatementStringConsumer consumer function} that will be called with the DDL
 * statements and the database to which they apply, grouped by database names. However, these will only be called based when the
 * databases are included by the database filters defined in the {@link MySqlConnectorConfig MySQL connector configuration}.
 *
 * @author Randall Hauch
 */
@NotThreadSafe
public class MySqlDatabaseSchema extends HistorizedRelationalDatabaseSchema {

    private final static Logger LOGGER = LoggerFactory.getLogger(MySqlDatabaseSchema.class);

    private final Set<String> ignoredQueryStatements = Collect.unmodifiableSet("BEGIN", "END", "FLUSH PRIVILEGES");
    private final DdlParser ddlParser;
    private final RelationalTableFilters filters;
    private final DdlChanges ddlChanges;

    /**
     * Create a schema component given the supplied {@link MySqlConnectorConfig MySQL connector configuration}.
     * The DDL statements passed to the schema are parsed and a logical model of the database schema is created.
     *
     */
    public MySqlDatabaseSchema(MySqlConnectorConfig connectorConfig, MySqlValueConverters valueConverter, TopicSelector<TableId> topicSelector,
                               SchemaNameAdjuster schemaNameAdjuster, boolean tableIdCaseInsensitive) {
        super(connectorConfig, topicSelector, connectorConfig.getTableFilters().dataCollectionFilter(), connectorConfig.getColumnFilter(),
                new TableSchemaBuilder(
                        valueConverter,
                        schemaNameAdjuster,
                        connectorConfig.customConverterRegistry(),
                        connectorConfig.getSourceInfoStructMaker().schema(),
                        connectorConfig.getSanitizeFieldNames()),
                tableIdCaseInsensitive, connectorConfig.getKeyMapper());

        this.ddlParser = new MySqlAntlrDdlParser(valueConverter, getTableFilter());
        this.ddlChanges = this.ddlParser.getDdlChanges();
        filters = connectorConfig.getTableFilters();
    }

    /**
     * Get all table names for all databases that are monitored whose events are captured by Debezium
     *
     * @return the array with the table names
     */
    public String[] monitoredTablesAsStringArray() {
        final Collection<TableId> tables = tableIds();
        String[] ret = new String[tables.size()];
        int i = 0;
        for (TableId table : tables) {
            ret[i++] = table.toString();
        }
        return ret;
    }

    /**
     * Set the system variables on the DDL parser.
     *
     * @param variables the system variables; may not be null but may be empty
     */
    public void setSystemVariables(Map<String, String> variables) {
        variables.forEach((varName, value) -> {
            ddlParser.systemVariables().setVariable(MySqlScope.SESSION, varName, value);
        });
    }

    /**
     * Get the system variables as known by the DDL parser.
     *
     * @return the system variables; never null
     */
    public SystemVariables systemVariables() {
        return ddlParser.systemVariables();
    }

    protected void appendDropTableStatement(StringBuilder sb, TableId tableId) {
        sb.append("DROP TABLE ").append(tableId).append(" IF EXISTS;").append(System.lineSeparator());
    }

    protected void appendCreateTableStatement(StringBuilder sb, Table table) {
        sb.append("CREATE TABLE ").append(table.id()).append(';').append(System.lineSeparator());
    }

    /**
     * Discard any currently-cached schemas and rebuild them using the filters.
     */
    protected void refreshSchemas() {
        clearSchemas();
        // Create TableSchema instances for any existing table ...
        this.tableIds().forEach(id -> {
            Table table = this.tableFor(id);
            buildAndRegisterSchema(table);
        });
    }

    public boolean isGlobalSetVariableStatement(String ddl, String databaseName) {
        return (databaseName == null || databaseName.isEmpty()) && ddl != null && ddl.toUpperCase().startsWith("SET ");
    }

    @Override
    public void applySchemaChange(SchemaChangeEvent schemaChange) {
        applySchemaChange(schemaChange, SchemaChangeEventConsumer.NOOP);
    }

    public void applySchemaChange(SchemaChangeEvent schemaChange, SchemaChangeEventConsumer schemaEventConsumer) {
        LOGGER.debug("Applying schema change event {}", schemaChange);

        if (schemaChange.getType() != SchemaChangeEventType.RAW) {
            LOGGER.debug("Schema change already processed by the database schema");
            return;
        }

        final String ddlStatements = schemaChange.getDdl();
        final String databaseName = schemaChange.getDatabase();

        if (ignoredQueryStatements.contains(ddlStatements)) {
            return;
        }

        try {
            this.ddlChanges.reset();
            this.ddlParser.setCurrentSchema(databaseName);
            this.ddlParser.parse(ddlStatements, tables());
        }
        catch (ParsingException | MultipleParsingExceptions e) {
            if (databaseHistory.skipUnparseableDdlStatements()) {
                LOGGER.warn("Ignoring unparseable DDL statement '{}': {}", ddlStatements, e);
            }
            else {
                throw e;
            }
        }
        final Set<TableId> changes = tables().drainChanges();
        // No need to send schema events or store DDL if no table has changed
        if (!databaseHistory.storeOnlyMonitoredTables() || isGlobalSetVariableStatement(ddlStatements, databaseName) || ddlChanges.anyMatch(filters)) {
            if (schemaEventConsumer != null) {

                // We are supposed to _also_ record the schema changes as SourceRecords, but these need to be filtered
                // by database. Unfortunately, the databaseName on the event might not be the same database as that
                // being modified by the DDL statements (since the DDL statements can have fully-qualified names).
                // Therefore, we have to look at each statement to figure out which database it applies and then
                // record the DDL statements (still in the same order) to those databases.

                if (!ddlChanges.isEmpty()) {
                    // We understood at least some of the DDL statements and can figure out to which database they apply.
                    // They also apply to more databases than 'databaseName', so we need to apply the DDL statements in
                    // the same order they were read for each _affected_ database, grouped together if multiple apply
                    // to the same _affected_ database...
                    ddlChanges.getEventsByDatabase((String dbName, List<Event> events) -> {
                        if (acceptableDatabase(dbName)) {
                            final String sanitizedDbName = (dbName == null) ? "" : dbName;
                            final Set<TableId> tableIds = new HashSet<>();
                            events.forEach(event -> {
                                final TableId tableId = getTableId(event);
                                if (tableId != null) {
                                    tableIds.add(tableId);
                                }
                            });
                            final Struct source = schemaChange.getSource();
                            source.put(AbstractSourceInfo.DATABASE_NAME_KEY, sanitizedDbName);
                            final String tableNamesStr = tableIds.stream().map(TableId::table).collect(Collectors.joining(","));
                            if (!tableNamesStr.isEmpty()) {
                                source.put(AbstractSourceInfo.TABLE_NAME_KEY, tableNamesStr);
                            }
                            schemaEventConsumer.consume(new SchemaChangeEvent(schemaChange.getPartition(),
                                    schemaChange.getOffset(), schemaChange.getSource(), sanitizedDbName,
                                    schemaChange.getSchema(), schemaChange.getDdl(), Collections.emptySet(), SchemaChangeEventType.DATABASE,
                                    schemaChange.isFromSnapshot()),
                                    tableIds);
                        }
                    });
                }
                else if (acceptableDatabase(databaseName)) {
                    schemaEventConsumer.consume(schemaChange, null);
                }
            }
            // Record the DDL statement so that we can later recover them if needed. We do this _after_ writing the
            // schema change records so that failure recovery (which is based on of the history) won't lose
            // schema change records.
            // We are storing either
            // - all DDLs if configured
            // - or global SET variables
            // - or DDLs for monitored objects
            if (!databaseHistory.storeOnlyMonitoredTables() || isGlobalSetVariableStatement(ddlStatements, databaseName)
                    || changes.stream().anyMatch(filters.dataCollectionFilter()::isIncluded)) {
                LOGGER.debug("Recorded DDL statements for database '{}': {}", databaseName, ddlStatements);
                record(schemaChange, schemaChange.getTableChanges());
            }
        }
        else {
            LOGGER.debug("Changes for DDL '{}' were filtered and not recorded in database history", ddlStatements);
        }

        // Figure out what changed ...
        TableChanges tableChanges = new TableChanges();
        changes.forEach(tableId -> {
            Table table = tableFor(tableId);
            if (table == null) { // removed
                removeSchema(tableId);
            }
            else {
                buildAndRegisterSchema(table);
                tableChanges.create(table);
            }
        });
    }

    private boolean acceptableDatabase(final String databaseName) {
        return filters.databaseFilter().test(databaseName) || databaseName == null || databaseName.isEmpty();
    }

    private TableId getTableId(Event event) {
        if (event instanceof TableEvent) {
            return ((TableEvent) event).tableId();
        }
        else if (event instanceof TableIndexEvent) {
            return ((TableIndexEvent) event).tableId();
        }
        return null;
    }

    @Override
    protected DdlParser getDdlParser() {
        return ddlParser;
    }

    /**
     * Return true if the database history entity exists
     */
    public boolean historyExists() {
        return databaseHistory.exists();
    }

    @Override
    public boolean storeOnlyMonitoredTables() {
        return databaseHistory.storeOnlyMonitoredTables();
    }
}
