// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import org.codehaus.groovy.runtime.IOGroovyMethods

suite("test_index_change_on_new_column") {
    def timeout = 60000
    def delta_time = 1000
    def alter_res = "null"
    def useTime = 0

    def wait_for_latest_op_on_table_finish = { table_name, OpTimeout ->
        for(int t = delta_time; t <= OpTimeout; t += delta_time){
            alter_res = sql """SHOW ALTER TABLE COLUMN WHERE TableName = "${table_name}" ORDER BY CreateTime DESC LIMIT 1;"""
            alter_res = alter_res.toString()
            if(alter_res.contains("FINISHED")) {
                sleep(3000) // wait change table state to normal
                logger.info(table_name + " latest alter job finished, detail: " + alter_res)
                break
            }
            useTime = t
            sleep(delta_time)
        }
        assertTrue(useTime <= OpTimeout, "wait_for_latest_op_on_table_finish timeout")
    }

    def wait_for_build_index_on_partition_finish = { table_name, OpTimeout ->
        for(int t = delta_time; t <= OpTimeout; t += delta_time){
            alter_res = sql """SHOW BUILD INDEX WHERE TableName = "${table_name}";"""
            def expected_finished_num = alter_res.size();
            def finished_num = 0;
            for (int i = 0; i < expected_finished_num; i++) {
                logger.info(table_name + " build index job state: " + alter_res[i][7] + i)
                if (alter_res[i][7] == "FINISHED") {
                    ++finished_num;
                }
            }
            if (finished_num == expected_finished_num) {
                logger.info(table_name + " all build index jobs finished, detail: " + alter_res)
                break
            }
            useTime = t
            sleep(delta_time)
        }
        assertTrue(useTime <= OpTimeout, "wait_for_latest_build_index_on_partition_finish timeout")
    }
    
    def tableName = "test_index_change_on_new_column"

    sql """ DROP TABLE IF EXISTS ${tableName} """
    sql """
        CREATE TABLE IF NOT EXISTS ${tableName} (
            `id` INT COMMENT "",
            `s` STRING COMMENT ""
        )
        DUPLICATE KEY(`id`) DISTRIBUTED BY HASH(`id`)
        PROPERTIES ( "replication_num" = "1" );
        """

    sql """ INSERT INTO ${tableName} VALUES
         (1, 'hello world')
        """
    
    // add new column
    sql """ alter table ${tableName} add column s1 varchar(50) default null after s; """
    
    qt_select1 """ SELECT * FROM ${tableName}; """

    // create inverted index on new column
    sql """ alter table ${tableName} add index idx_s1(s1) USING INVERTED PROPERTIES('parser' = 'english')"""
    wait_for_latest_op_on_table_finish(tableName, timeout)

    sql """ INSERT INTO ${tableName} VALUES
            (2, 'hello wold', 'welcome to the world')
        """
    // build inverted index on new column
    if (!isCloudMode()) {
        sql """ build index idx_s1 on ${tableName} """
        wait_for_build_index_on_partition_finish(tableName, timeout)
    }

    def show_result = sql "show index from ${tableName}"
    logger.info("show index from " + tableName + " result: " + show_result)
    assertEquals(show_result.size(), 1)
    assertEquals(show_result[0][2], "idx_s1")

    qt_select2 """ SELECT * FROM ${tableName} order by id; """
    qt_select3 """ SELECT /*+SET_VAR(enable_fallback_on_missing_inverted_index=false) */ * FROM ${tableName} where s1 match 'welcome'; """

    tableName = "test_index_change_on_new_column1"

    sql """ DROP TABLE IF EXISTS ${tableName} """
    sql """
        CREATE TABLE IF NOT EXISTS ${tableName} (
            `id` INT COMMENT "",
            `s` STRING COMMENT "",
            INDEX idx_s(s) USING INVERTED
        )
        DUPLICATE KEY(`id`) DISTRIBUTED BY HASH(`id`)
        PROPERTIES ( "replication_num" = "1" );
        """

    sql """ INSERT INTO ${tableName} VALUES
         (1, 'hello world')
        """

    // add new column
    sql """ alter table ${tableName} add column s1 varchar(50) default null after s; """

    qt_select1 """ SELECT * FROM ${tableName}; """

    // create inverted index on new column
    sql """ alter table ${tableName} add index idx_s1(s1) USING INVERTED PROPERTIES('parser' = 'english')"""
    wait_for_latest_op_on_table_finish(tableName, timeout)

    sql """ INSERT INTO ${tableName} VALUES
            (2, 'hello wold', 'welcome to the world')
        """
    // build inverted index on new column
    if (!isCloudMode()) {
        sql """ build index idx_s1 on ${tableName} """
        wait_for_build_index_on_partition_finish(tableName, timeout)
    }

    show_result = sql "show index from ${tableName}"
    logger.info("show index from " + tableName + " result: " + show_result)
    assertEquals(show_result.size(), 2)
    assertEquals(show_result[0][2], "idx_s")
    assertEquals(show_result[1][2], "idx_s1")
    qt_select2 """ SELECT * FROM ${tableName} order by id; """
    qt_select3 """ SELECT /*+SET_VAR(enable_fallback_on_missing_inverted_index=false) */ * FROM ${tableName} where s1 match 'welcome'; """
    qt_select4 """ SELECT /*+SET_VAR(enable_fallback_on_missing_inverted_index=false) */ * FROM ${tableName} where s match 'hello world'; """

    tableName = "test_index_change_on_new_column1_index_v1"

    sql """ DROP TABLE IF EXISTS ${tableName} """
    sql """
        CREATE TABLE IF NOT EXISTS ${tableName} (
            `id` INT COMMENT "",
            `s` STRING COMMENT "",
            INDEX idx_s(s) USING INVERTED
        )
        DUPLICATE KEY(`id`) DISTRIBUTED BY HASH(`id`)
        PROPERTIES ( "inverted_index_storage_format" = "v1", "replication_num" = "1" );
        """

    sql """ INSERT INTO ${tableName} VALUES
         (1, 'hello world')
        """

    // add new column
    sql """ alter table ${tableName} add column s1 varchar(50) default null after s; """

    qt_select1 """ SELECT * FROM ${tableName}; """

    // create inverted index on new column
    sql """ alter table ${tableName} add index idx_s1(s1) USING INVERTED PROPERTIES('parser' = 'english')"""
    wait_for_latest_op_on_table_finish(tableName, timeout)

    sql """ INSERT INTO ${tableName} VALUES
            (2, 'hello wold', 'welcome to the world')
        """
    // build inverted index on new column
    if (!isCloudMode()) {
        sql """ build index idx_s1 on ${tableName} """
        wait_for_build_index_on_partition_finish(tableName, timeout)
    }

    show_result = sql "show index from ${tableName}"
    logger.info("show index from " + tableName + " result: " + show_result)
    assertEquals(show_result.size(), 2)
    assertEquals(show_result[0][2], "idx_s")
    assertEquals(show_result[1][2], "idx_s1")
    qt_select2 """ SELECT * FROM ${tableName} order by id; """
    qt_select3 """ SELECT /*+SET_VAR(enable_fallback_on_missing_inverted_index=false) */ * FROM ${tableName} where s1 match 'welcome'; """
    qt_select4 """ SELECT /*+SET_VAR(enable_fallback_on_missing_inverted_index=false) */ * FROM ${tableName} where s match 'hello world'; """
}
