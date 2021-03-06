package space.exploration.mars.rover.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;
import org.junit.Ignore;
import space.exploration.mars.rover.bootstrap.MarsMissionLaunch;
import sun.awt.SunGraphicsCallback;

import java.util.concurrent.TimeUnit;

@Ignore
public class PhotoQueryServiceTest extends TestCase {
    private PhotoQueryService photoQueryService = new PhotoQueryService();

    public void setUp() {
        MarsMissionLaunch.configureLogging(true);
        photoQueryService.setAuthenticationKey("TLcQa8H1EH0nc7teUJQwP7cIJyqnXtwE25A2vXHP");
        photoQueryService.setEarthStartDate(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(360));
    }

    @org.junit.Test
    public void testFHAZCamera() {
        photoQueryService.setCamId("FHAZ");
        photoQueryService.setSol(1210);
        System.out.println("Formatted query = " + photoQueryService.getQueryString());
        photoQueryService.executeQuery();
        System.out.println(photoQueryService.getQueryResponseType().toString());

        String     responseString = photoQueryService.getResponseAsString();
        JsonParser jsonParser     = new JsonParser();
        JsonObject jsonObject     = (jsonParser.parse(responseString)).getAsJsonObject();

        jsonObject.getAsJsonArray("photos");
        JsonArray jsonElements = jsonObject.getAsJsonArray("photos");
        for (JsonElement jsonElement : jsonElements) {
            System.out.println("=================================");
            System.out.println(jsonElement);
        }

        System.out.println(responseString);
    }
}
