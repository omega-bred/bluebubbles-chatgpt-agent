package io.breland.bbagent.server.controllers;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppleAssociationController {
  private static final String TEAM_ID = "U2Q8X6GTU9";
  private static final String MAIN_APP_ID = TEAM_ID + ".land.bre.bluechat.ios";
  private static final String CLIP_APP_ID = TEAM_ID + ".land.bre.bluechat.ios.Clip";

  @GetMapping(
      path = {"/.well-known/apple-app-site-association", "/apple-app-site-association"},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
    return ResponseEntity.ok(
        Map.of(
            "applinks",
            Map.of(
                "apps",
                List.of(),
                "details",
                List.of(
                    Map.of(
                        "appID", MAIN_APP_ID, "paths", List.of("/account/link*", "/appclip/*")))),
            "appclips",
            Map.of("apps", List.of(CLIP_APP_ID))));
  }
}
