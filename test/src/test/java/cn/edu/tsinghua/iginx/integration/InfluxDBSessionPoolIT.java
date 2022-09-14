package cn.edu.tsinghua.iginx.integration;

import java.util.LinkedHashMap;

public class InfluxDBSessionPoolIT  extends BaseSessionPoolIT {
    public InfluxDBSessionPoolIT() {
        super();
        this.defaultPort2 = 8087;
        this.isAbleToDelete = false;
        this.storageEngineType = "influxdb";
        this.extraParams = new LinkedHashMap<>();
        this.extraParams.put("url", "http://localhost:8087/");
        this.extraParams.put("token", "testToken");
        this.extraParams.put("organization", "testOrg");
    }
}
