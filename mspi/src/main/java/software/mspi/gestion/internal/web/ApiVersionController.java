package software.mspi.gestion.internal.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ApiVersionController {

    @GetMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
    ApiVersion version() {
        return new ApiVersion("v1");
    }

    @RequestMapping(path = "/api/v1", method = RequestMethod.HEAD, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> versionHeaders() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).build();
    }

    record ApiVersion(String version) {}
}
