package no.sjakkportal.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TvController {

    private final TvService tvService;

    public TvController(TvService tvService) {
        this.tvService = tvService;
    }

    @GetMapping("/api/tv")
    TvService.TvState current() {
        return tvService.current();
    }
}
