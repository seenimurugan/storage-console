package com.nila.storageconsole.system;

import com.nila.storageconsole.k8s.HddProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final HddProbeService hdd;

    public SystemController(HddProbeService hdd) {
        this.hdd = hdd;
    }

    @GetMapping("/hdd-status")
    public HddProbeService.Status hddStatus() {
        return hdd.status();
    }
}
