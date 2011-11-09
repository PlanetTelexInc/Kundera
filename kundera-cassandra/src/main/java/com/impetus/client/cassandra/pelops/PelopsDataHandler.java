/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.cassandra.pelops;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.persistence.PersistenceException;

import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.scale7.cassandra.pelops.Bytes;
import org.scale7.cassandra.pelops.Selector;

import com.impetus.kundera.Constants;
import com.impetus.kundera.cache.ElementCollectionCacheManager;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.DataHandler;
import com.impetus.kundera.db.DataRow;
import com.impetus.kundera.metadata.MetadataUtils;
import com.impetus.kundera.metadata.model.EmbeddedColumn;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.Relation;
import com.impetus.kundera.persistence.EntityResolver;
import com.impetus.kundera.property.PropertyAccessException;
import com.impetus.kundera.property.PropertyAccessorFactory;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.proxy.EnhancedEntity;

/**
 * Provides Pelops utility methods for data held in Column family based stores
 * 
 * @author amresh.singh
 */
public class PelopsDataHandler extends DataHandler
{
    private Client client;

    public PelopsDataHandler(Client client)
    {
        super();
        this.client = client;
    }

    private static Log log = LogFactory.getLog(PelopsDataHandler.class);

    public <E> E fromThriftRow(Selector selector, Class<E> clazz, EntityMetadata m, String rowKey) throws Exception
    {
        List<String> superColumnNames = m.getEmbeddedColumnFieldNames();
        E e = null;
        if (!superColumnNames.isEmpty())
        {
            List<SuperColumn> thriftSuperColumns = selector.getSuperColumnsFromRow(m.getTableName(), rowKey,
                    Selector.newColumnsPredicateAll(true, 10000), ConsistencyLevel.ONE);
            e = fromSuperColumnThriftRow(clazz, m, new ThriftRow(rowKey, m.getTableName(), null, thriftSuperColumns));

        }
        else
        {
            List<Column> columns = selector.getColumnsFromRow(m.getTableName(), new Bytes(rowKey.getBytes()),
                    Selector.newColumnsPredicateAll(true, 10), ConsistencyLevel.ONE);

            e = fromColumnThriftRow(clazz, m, new ThriftRow(rowKey, m.getTableName(), columns, null));

        }
        return e;
    }

    public <E> List<E> fromThriftRow(Selector selector, Class<E> clazz, EntityMetadata m, String... rowIds)
            throws Exception
    {
        List<E> entities = new ArrayList<E>();
        for (String rowKey : rowIds)
        {
            E e = fromThriftRow(selector, clazz, m, rowKey);
            entities.add(e);
        }
        return entities;
    }

    /**
     * From thrift row.
     * 
     * @param <E>
     *            the element type
     * @param clazz
     *            the clazz
     * @param m
     *            the m
     * @param tr
     *            the cr
     * @return the e
     * @throws Exception
     *             the exception
     */
    // TODO: this is a duplicate code snippet and we need to refactor this.
    public <E> E fromThriftRow(Class<E> clazz, EntityMetadata m, DataRow<SuperColumn> tr) throws Exception
    {

        // Instantiate a new instance
        E e = clazz.newInstance();

        // Set row-key. Note: @Id is always String.
        PropertyAccessorHelper.set(e, m.getIdColumn().getField(), tr.getId());

        // Get a name->field map for super-columns
        Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
        Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();
        MetadataUtils.populateColumnAndSuperColumnMaps(m, columnNameToFieldMap, superColumnNameToFieldMap);

        Collection embeddedCollection = null;
        Field embeddedCollectionField = null;
        for (SuperColumn sc : tr.getColumns())
        {

            String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
            String scNamePrefix = null;

            if (scName.indexOf(Constants.EMBEDDED_COLUMN_NAME_DELIMITER) != -1)
            {
                scNamePrefix = MetadataUtils.getEmbeddedCollectionPrefix(scName);
                embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);
                embeddedCollection = MetadataUtils.getEmbeddedCollectionInstance(embeddedCollectionField);

                String scFieldName = scName.substring(0, scName.indexOf(Constants.EMBEDDED_COLUMN_NAME_DELIMITER));
                Field superColumnField = e.getClass().getDeclaredField(scFieldName);
                if (!superColumnField.isAccessible())
                {
                    superColumnField.setAccessible(true);
                }
                // Collection embeddedCollection = null;
                if (superColumnField.getType().equals(List.class))
                {
                    embeddedCollection = new ArrayList();
                }
                else if (superColumnField.getType().equals(Set.class))
                {
                    embeddedCollection = new HashSet();
                }

                Object embeddedObject = populateEmbeddedObject(sc, m);
                embeddedCollection.add(embeddedObject);
                superColumnField.set(e, embeddedCollection);
            }
            else
            {
                boolean intoRelations = false;
                if (scName.equals(Constants.FOREIGN_KEY_EMBEDDED_COLUMN_NAME))
                {
                    intoRelations = true;
                }

                for (Column column : sc.getColumns())
                {
                    String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                    byte[] value = column.getValue();

                    if (value == null)
                    {
                        continue;
                    }

                    if (intoRelations)
                    {
                        Relation relation = m.getRelation(name);

                        String foreignKeys = PropertyAccessorFactory.STRING.fromBytes(value);
                        Set<String> keys = MetadataUtils.deserializeKeys(foreignKeys);

                        // Commented as it has been moved to EM
                        // TODO: Correct this

                        // populateForeignEntities(e, tr.getId(), relation,
                        // keys.toArray(new String[0]));

                    }
                    else
                    {
                        // set value of the field in the bean
                        Field field = columnNameToFieldMap.get(name);
                        Object embeddedObject = PropertyAccessorHelper.getObject(e, scName);
                        PropertyAccessorHelper.set(embeddedObject, field, value);
                    }
                }
            }
        }
        return e;
    }

    /**
     * Fetches data held in Thrift row columns and populates to Entity objects
     * 
     * @param <E>
     *            the element type
     * @param em
     *            the em
     * @param clazz
     *            the clazz
     * @param m
     *            the m
     * @param thriftRow
     *            the cr
     * @return the e
     * @throws Exception
     *             the exception
     */
    public <E> E fromColumnThriftRow(Class<E> clazz, EntityMetadata m, ThriftRow thriftRow) throws Exception
    {

        // Instantiate a new instance
        Object entity = clazz.newInstance();

        // Map to hold property-name=>foreign-entity relations
        Map<String, Set<String>> foreignKeysMap = new HashMap<String, Set<String>>();

        // Set row-key. Note: @Id is always String.
        PropertyAccessorHelper.set(entity, m.getIdColumn().getField(), thriftRow.getId());

        // Iterate through each column
        for (Column c : thriftRow.getColumns())
        {
            String thriftColumnName = PropertyAccessorFactory.STRING.fromBytes(c.getName());
            byte[] thriftColumnValue = c.getValue();

            if (null == thriftColumnValue)
            {
                continue;
            }

            // Check if this is a property, or a column representing foreign
            // keys
            com.impetus.kundera.metadata.model.Column column = m.getColumn(thriftColumnName);
            if (null == column)
            {
                // Set of all foreign keys for this field
                Set<String> foreignKeys = MetadataUtils.deserializeKeys(PropertyAccessorFactory.STRING
                        .fromBytes(thriftColumnValue));
                Field foreignKeyField = column.getField();

                foreignKeysMap.put(foreignKeyField.getName(), foreignKeys);

            }
            else
            {
                try
                {
                    PropertyAccessorHelper.set(entity, column.getField(), thriftColumnValue);
                }
                catch (PropertyAccessException pae)
                {
                    log.warn(pae.getMessage());
                }
            }
        }

        EnhancedEntity e = EntityResolver.getEnhancedEntity(entity, thriftRow.getId(), foreignKeysMap);

        return (E) e;
    }

    /**
     * Fetches data held in Thrift row super columns and populates to Entity
     * objects
     */
    public <E> E fromSuperColumnThriftRow(Class<E> clazz, EntityMetadata m, ThriftRow tr) throws Exception
    {

        // Instantiate a new instance
        Object entity = clazz.newInstance();

        // Map to hold property-name=>foreign-entity relations
        Map<String, Set<String>> foreignKeysMap = new HashMap<String, Set<String>>();

        // Set row-key. Note: @Id is always String.
        PropertyAccessorHelper.set(entity, m.getIdColumn().getField(), tr.getId());

        // Get a name->field map for super-columns
        Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
        Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();
        MetadataUtils.populateColumnAndSuperColumnMaps(m, columnNameToFieldMap, superColumnNameToFieldMap);

        // Add all super columns to entity
        Collection embeddedCollection = null;
        Field embeddedCollectionField = null;
        for (SuperColumn sc : tr.getSuperColumns())
        {
            String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
            String scNamePrefix = null;

            // If this super column is variable in number (name#sequence format)
            if (scName.indexOf(Constants.EMBEDDED_COLUMN_NAME_DELIMITER) != -1)
            {
                scNamePrefix = MetadataUtils.getEmbeddedCollectionPrefix(scName);
                embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);

                if (embeddedCollection == null)
                {
                    embeddedCollection = MetadataUtils.getEmbeddedCollectionInstance(embeddedCollectionField);
                }

                Object embeddedObject = MetadataUtils.getEmbeddedGenericObjectInstance(embeddedCollectionField);

                for (Column column : sc.getColumns())
                {
                    String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                    byte[] value = column.getValue();
                    if (value == null)
                    {
                        continue;
                    }
                    Field columnField = columnNameToFieldMap.get(name);
                    PropertyAccessorHelper.set(embeddedObject, columnField, value);

                }
                embeddedCollection.add(embeddedObject);

                // Add this embedded object to cache
                ElementCollectionCacheManager.getInstance().addElementCollectionCacheMapping(tr.getId(),
                        embeddedObject, scName);
            }
            else
            {
                /*
                 * Check whether this super column represents a foreign key
                 * list, if yes, set foreign key set in enhanced entity along
                 * with entity object, Otherwise, Just set entity object.
                 */
                boolean intoRelations = false;
                if (scName.equals(Constants.FOREIGN_KEY_EMBEDDED_COLUMN_NAME))
                {
                    intoRelations = true;
                }

                // For relations, fetch foreign keys from foreign key super
                // column and populate related entities into parent entity
                if (intoRelations)
                {

                    for (Column column : sc.getColumns())
                    {
                        String columnName = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                        byte[] columnValue = column.getValue();

                        if (columnValue == null)
                        {
                            continue;
                        }
                        else
                        {

                            // Set of all foreign keys for this field
                            Set<String> foreignKeys = MetadataUtils.deserializeKeys(PropertyAccessorFactory.STRING
                                    .fromBytes(columnValue));

                            Relation relation = m.getRelation(columnName);
                            Field foreignKeyField = relation.getProperty();
                            foreignKeysMap.put(foreignKeyField.getName(), foreignKeys);

                        }
                    }
                }
                else
                {
                    // For embedded super columns, create embedded entities and
                    // add them to parent entity
                    Field superColumnField = superColumnNameToFieldMap.get(scName);
                    Class superColumnClass = superColumnField.getType();
                    Object superColumnObj = superColumnClass.newInstance();

                    for (Column column : sc.getColumns())
                    {
                        String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                        byte[] value = column.getValue();

                        Field columnField = columnNameToFieldMap.get(name);
                        PropertyAccessorHelper.set(superColumnObj, columnField, value);
                    }
                    PropertyAccessorHelper.set(entity, superColumnField, superColumnObj);

                }
            }

        }

        if (embeddedCollection != null && !embeddedCollection.isEmpty())
        {
            PropertyAccessorHelper.set(entity, embeddedCollectionField, embeddedCollection);
        }

        EnhancedEntity e = EntityResolver.getEnhancedEntity(entity, tr.getId(), foreignKeysMap);
        return (E) e;
    }

    public Object populateEmbeddedObject(SuperColumn sc, EntityMetadata m) throws Exception
    {
        Field embeddedCollectionField = null;
        Object embeddedObject = null;
        String scName = PropertyAccessorFactory.STRING.fromBytes(sc.getName());
        String scNamePrefix = null;

        // Get a name->field map for super-columns
        Map<String, Field> columnNameToFieldMap = new HashMap<String, Field>();
        Map<String, Field> superColumnNameToFieldMap = new HashMap<String, Field>();
        MetadataUtils.populateColumnAndSuperColumnMaps(m, columnNameToFieldMap, superColumnNameToFieldMap);

        // If this super column is variable in number (name#sequence format)
        if (scName.indexOf(Constants.EMBEDDED_COLUMN_NAME_DELIMITER) != -1)
        {
            StringTokenizer st = new StringTokenizer(scName, Constants.EMBEDDED_COLUMN_NAME_DELIMITER);
            if (st.hasMoreTokens())
            {
                scNamePrefix = st.nextToken();
            }

            embeddedCollectionField = superColumnNameToFieldMap.get(scNamePrefix);
            Class<?> embeddedClass = PropertyAccessorHelper.getGenericClass(embeddedCollectionField);

            // must have a default no-argument constructor
            try
            {
                embeddedClass.getConstructor();
            }
            catch (NoSuchMethodException nsme)
            {
                throw new PersistenceException(embeddedClass.getName()
                        + " is @Embeddable and must have a default no-argument constructor.");
            }
            embeddedObject = embeddedClass.newInstance();

            for (Column column : sc.getColumns())
            {
                String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                byte[] value = column.getValue();
                if (value == null)
                {
                    continue;
                }
                Field columnField = columnNameToFieldMap.get(name);
                PropertyAccessorHelper.set(embeddedObject, columnField, value);
            }

        }
        else
        {
            Field superColumnField = superColumnNameToFieldMap.get(scName);
            Class superColumnClass = superColumnField.getType();
            embeddedObject = superColumnClass.newInstance();

            for (Column column : sc.getColumns())
            {
                String name = PropertyAccessorFactory.STRING.fromBytes(column.getName());
                byte[] value = column.getValue();

                if (value == null)
                {
                    continue;
                }
                // set value of the field in the bean
                Field columnField = columnNameToFieldMap.get(name);
                PropertyAccessorHelper.set(embeddedObject, columnField, value);

            }
        }
        return embeddedObject;
    }

    /**
     * Helper method to convert @Entity to ThriftRow.
     * 
     * @param e
     *            the e
     * @param columnsLst
     *            the columns lst
     * @param columnFamily
     *            the colmun family
     * @return the base data accessor. thrift row
     * @throws Exception
     *             the exception
     */
    public ThriftRow toThriftRow(PelopsClient client, EnhancedEntity e, EntityMetadata m, String columnFamily)
            throws Exception
    {
        // timestamp to use in thrift column objects
        long timestamp = System.currentTimeMillis();

        ThriftRow tr = new ThriftRow();

        tr.setColumnFamilyName(columnFamily); // column-family name
        tr.setId(e.getId()); // Id

        // Add super columns to thrift row
        addSuperColumnsToThriftRow(timestamp, client, tr, m, e);

        // Add columns to thrift row
        addColumnsToThriftRow(timestamp, tr, m, e);

        // Add relations entities as Foreign keys to a new super column created
        // internally
        addRelationshipsToThriftRow(timestamp, tr, e, m);

        return tr;
    }

    private void addColumnsToThriftRow(long timestamp, ThriftRow tr, EntityMetadata m, EnhancedEntity e)
            throws Exception
    {
        List<Column> columns = new ArrayList<Column>();

        // Iterate through each column-meta and populate that with field values
        for (com.impetus.kundera.metadata.model.Column column : m.getColumnsAsList())
        {
            Field field = column.getField();
            String name = column.getName();
            try
            {
                byte[] value = PropertyAccessorHelper.get(e.getEntity(), field);
                Column col = new Column();
                col.setName(PropertyAccessorFactory.STRING.toBytes(name));
                col.setValue(value);
                col.setTimestamp(timestamp);
                columns.add(col);
            }
            catch (PropertyAccessException exp)
            {
                log.warn(exp.getMessage());
            }

        }
        tr.setColumns(columns);

    }

    private void addSuperColumnsToThriftRow(long timestamp, PelopsClient client, ThriftRow tr, EntityMetadata m,
            EnhancedEntity e) throws Exception
    {
        // Iterate through Super columns
        for (EmbeddedColumn superColumn : m.getEmbeddedColumnsAsList())
        {
            Field superColumnField = superColumn.getField();
            Object superColumnObject = PropertyAccessorHelper.getObject(e.getEntity(), superColumnField);

            // If Embedded object is a Collection, there will be variable number
            // of super columns one for each object in collection.
            // Key for each super column will be of the format "<Embedded object
            // field name>#<Unique sequence number>

            // On the other hand, if embedded object is not a Collection, it
            // would simply be embedded as ONE super column.
            String superColumnName = null;
            if (superColumnObject == null)
            {
                continue;
            }
            if (superColumnObject instanceof Collection)
            {

                ElementCollectionCacheManager ecCacheHandler = ElementCollectionCacheManager.getInstance();

                // Check whether it's first time insert or updation
                if (ecCacheHandler.isCacheEmpty())
                { // First time insert
                    int count = 0;
                    for (Object obj : (Collection) superColumnObject)
                    {
                        superColumnName = superColumn.getName() + Constants.EMBEDDED_COLUMN_NAME_DELIMITER + count;
                        SuperColumn thriftSuperColumn = buildThriftSuperColumn(superColumnName, timestamp, superColumn,
                                obj);
                        tr.addSuperColumn(thriftSuperColumn);

                        count++;
                    }
                }
                else
                { // Updation
                  // Check whether this object is already in cache,
                  // which
                  // means we already have a super column
                  // Otherwise we need to generate a fresh super
                  // column name
                    int lastEmbeddedObjectCount = ecCacheHandler.getLastElementCollectionObjectCount(e.getId());
                    for (Object obj : (Collection) superColumnObject)
                    {
                        superColumnName = ecCacheHandler.getElementCollectionObjectName(e.getId(), obj);
                        if (superColumnName == null)
                        { // Fresh row
                            superColumnName = superColumn.getName() + Constants.EMBEDDED_COLUMN_NAME_DELIMITER
                                    + (++lastEmbeddedObjectCount);
                        }
                        SuperColumn thriftSuperColumn = buildThriftSuperColumn(superColumnName, timestamp, superColumn,
                                obj);
                        tr.addSuperColumn(thriftSuperColumn);
                    }
                }

            }
            else
            {
                superColumnName = superColumn.getName();
                SuperColumn thriftSuperColumn = buildThriftSuperColumn(superColumnName, timestamp, superColumn,
                        superColumnObject);
                tr.addSuperColumn(thriftSuperColumn);
            }

        }

    }

    /**
     * For super column families, all relationships are saved as columns, in one
     * additional super column used internally.
     * 
     * @throws PropertyAccessException
     */
    public void addRelationshipsToThriftRow(long timestamp, ThriftRow tr, EnhancedEntity e, EntityMetadata m)
            throws PropertyAccessException
    {
        if (!m.getEmbeddedColumnsAsList().isEmpty())
        {
            List<Column> columns = new ArrayList<Column>();
            addForeignkeysToColumns(timestamp, e, columns);
            if (!columns.isEmpty())
            {
                SuperColumn superCol = new SuperColumn();
                superCol.setName(PropertyAccessorFactory.STRING.toBytes(Constants.FOREIGN_KEY_EMBEDDED_COLUMN_NAME));
                superCol.setColumns(columns);
                tr.addSuperColumn(superCol);
            }
        }

        if (!m.getColumnsAsList().isEmpty())
        {
            List<Column> columns = tr.getColumns();
            addForeignkeysToColumns(timestamp, e, columns);
            tr.setColumns(columns);
        }

    }

    private SuperColumn buildThriftSuperColumn(String superColumnName, long timestamp, EmbeddedColumn superColumn,
            Object superColumnObject) throws PropertyAccessException
    {
        List<Column> thriftColumns = new ArrayList<Column>();
        for (com.impetus.kundera.metadata.model.Column column : superColumn.getColumns())
        {
            Field field = column.getField();
            String name = column.getName();

            try
            {
                byte[] value = PropertyAccessorHelper.get(superColumnObject, field);
                if (null != value)
                {
                    Column thriftColumn = new Column();
                    thriftColumn.setName(PropertyAccessorFactory.STRING.toBytes(name));
                    thriftColumn.setValue(value);
                    thriftColumn.setTimestamp(timestamp);
                    thriftColumns.add(thriftColumn);
                }
            }
            catch (PropertyAccessException exp)
            {
                log.warn(exp.getMessage());
            }
        }
        SuperColumn thriftSuperColumn = new SuperColumn();
        thriftSuperColumn.setName(PropertyAccessorFactory.STRING.toBytes(superColumnName));
        thriftSuperColumn.setColumns(thriftColumns);

        return thriftSuperColumn;
    }

    /**
     * All relationships in a column family are saved as additional column
     * internally, one for each relationship entity. Columns value is row key of
     * relationship entity for 1-to-1 relationship and ~ delimited row keys of
     * relationship entities for 1-M relationship
     * 
     * @throws PropertyAccessException
     */
    public void addForeignkeysToColumns(long timestamp, EnhancedEntity e, List<Column> columns)
            throws PropertyAccessException
    {
        // Add relationships as foreign keys
        for (Map.Entry<String, Set<String>> entry : e.getForeignKeysMap().entrySet())
        {
            String property = entry.getKey();
            Set<String> foreignKeys = entry.getValue();

            String keys = MetadataUtils.serializeKeys(foreignKeys);
            if (null != keys)
            {
                Column col = new Column();

                col.setName(PropertyAccessorFactory.STRING.toBytes(property));
                col.setValue(PropertyAccessorFactory.STRING.toBytes(keys));
                col.setTimestamp(timestamp);
                columns.add(col);
            }
        }
    }

    /**
     * Utility class that represents a row in Cassandra DB.
     * 
     * @author animesh.kumar
     */
    public class ThriftRow
    {

        /** Id of the row. */
        private String id;

        /** name of the family. */
        private String columnFamilyName;

        /** list of thrift columns from the row. */
        private List<Column> columns;

        /** list of thrift super columns columns from the row. */
        private List<SuperColumn> superColumns;

        /**
         * default constructor.
         */
        public ThriftRow()
        {
            columns = new ArrayList<Column>();
            superColumns = new ArrayList<SuperColumn>();
        }

        /**
         * The Constructor.
         * 
         * @param id
         *            the id
         * @param columnFamilyName
         *            the column family name
         * @param columns
         *            the columns
         * @param superColumns
         *            the super columns
         */
        public ThriftRow(String id, String columnFamilyName, List<Column> columns, List<SuperColumn> superColumns)
        {
            this.id = id;
            this.columnFamilyName = columnFamilyName;
            if (columns != null)
            {
                this.columns = columns;
            }

            if (superColumns != null)
            {
                this.superColumns = superColumns;
            }

        }

        /**
         * Gets the id.
         * 
         * @return the id
         */
        public String getId()
        {
            return id;
        }

        /**
         * Sets the id.
         * 
         * @param id
         *            the key to set
         */
        public void setId(String id)
        {
            this.id = id;
        }

        /**
         * Gets the column family name.
         * 
         * @return the columnFamilyName
         */
        public String getColumnFamilyName()
        {
            return columnFamilyName;
        }

        /**
         * Sets the column family name.
         * 
         * @param columnFamilyName
         *            the columnFamilyName to set
         */
        public void setColumnFamilyName(String columnFamilyName)
        {
            this.columnFamilyName = columnFamilyName;
        }

        /**
         * Gets the columns.
         * 
         * @return the columns
         */
        public List<Column> getColumns()
        {
            return columns;
        }

        /**
         * Sets the columns.
         * 
         * @param columns
         *            the columns to set
         */
        public void setColumns(List<Column> columns)
        {
            this.columns = columns;
        }

        /**
         * Adds the column.
         * 
         * @param column
         *            the column
         */
        public void addColumn(Column column)
        {
            columns.add(column);
        }

        /**
         * Gets the super columns.
         * 
         * @return the superColumns
         */
        public List<SuperColumn> getSuperColumns()
        {
            return superColumns;
        }

        /**
         * Sets the super columns.
         * 
         * @param superColumns
         *            the superColumns to set
         */
        public void setSuperColumns(List<SuperColumn> superColumns)
        {
            this.superColumns = superColumns;
        }

        /**
         * Adds the super column.
         * 
         * @param superColumn
         *            the super column
         */
        public void addSuperColumn(SuperColumn superColumn)
        {
            this.superColumns.add(superColumn);
        }

    }

}
