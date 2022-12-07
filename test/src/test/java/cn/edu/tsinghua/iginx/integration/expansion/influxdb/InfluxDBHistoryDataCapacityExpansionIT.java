package cn.edu.tsinghua.iginx.integration.expansion.influxdb;

import cn.edu.tsinghua.iginx.exceptions.SessionException;
import cn.edu.tsinghua.iginx.integration.SQLSessionIT;
import cn.edu.tsinghua.iginx.integration.expansion.unit.SQLTestTools;
import cn.edu.tsinghua.iginx.session.Session;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InfluxDBHistoryDataCapacityExpansionIT {
    private static final Logger logger = LoggerFactory.getLogger(InfluxDBHistoryDataCapacityExpansionIT.class);

    private static Session session;

    private String ENGINE_TYPE = "influxdb";

    public InfluxDBHistoryDataCapacityExpansionIT(String engineType) {
        this.ENGINE_TYPE = engineType;
    }

    @BeforeClass
    public static void setUp() {
        session = new Session("127.0.0.1", 6888, "root", "root");
        try {
            session.openSession();
        } catch (SessionException e) {
            logger.error(e.getMessage());
        }
    }

    @AfterClass
    public static void tearDown() {
        try {
            session.closeSession();
        } catch (SessionException e) {
            logger.error(e.getMessage());
        }
    }

    @Test
    public void testSchemaPrefix() throws Exception {
        session.executeSql("ADD STORAGEENGINE (\"127.0.0.1\", 8060, \"" + ENGINE_TYPE + "\", \"url:http://localhost:8086/ , username:user, password:12345678, sessionPoolSize:20, schema_prefix:expansion, has_data:true, is_read_only:true, token:testToken, organization:testOrg\");");

        String statement = "select * from expansion.data_center";
        String expect = "ResultSets:\n" +
                "+-----------------------+---------------------------------------------------------------+---------------------------------------------------------------+---------------------------------------------------------------+---------------------------------------------------------+---------------------------------------------------------+---------------------------------------------------------+\n" +
                "|                   Time|expansion.data_center.cpu.temperature{host=1,rack=A,room=ROOMA}|expansion.data_center.cpu.temperature{host=2,rack=B,room=ROOMA}|expansion.data_center.cpu.temperature{host=4,rack=B,room=ROOMB}|expansion.data_center.cpu.usage{host=1,rack=A,room=ROOMA}|expansion.data_center.cpu.usage{host=2,rack=B,room=ROOMA}|expansion.data_center.cpu.usage{host=4,rack=B,room=ROOMB}|\n" +
                "+-----------------------+---------------------------------------------------------------+---------------------------------------------------------------+---------------------------------------------------------------+---------------------------------------------------------+---------------------------------------------------------+---------------------------------------------------------+\n" +
                "|1970-01-01T08:16:40.000|                                                           56.4|                                                           55.1|                                                           null|                                                     66.3|                                                     72.1|                                                     null|\n" +
                "|1970-01-01T08:21:40.000|                                                           56.2|                                                           null|                                                           99.8|                                                     67.1|                                                     null|                                                     22.1|\n" +
                "+-----------------------+---------------------------------------------------------------+---------------------------------------------------------------+---------------------------------------------------------------+---------------------------------------------------------+---------------------------------------------------------+---------------------------------------------------------+\n" +
                "Total line number = 8\n";
        SQLTestTools.executeAndCompare(session, statement, expect);
    }
}