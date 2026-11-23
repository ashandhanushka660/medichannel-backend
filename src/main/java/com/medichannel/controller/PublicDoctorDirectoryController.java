package com.medichannel.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medichannel.dto.directory.DoctorDirectoryEntry;
import com.medichannel.service.PublicDoctorDirectoryService;

/**
 * Guest-facing hospital roster. This is intentionally public: a visitor must
 * be able to see doctors, rooms, fees, clinic hours and live queue numbers
 * before deciding whether to register or sign in.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicDoctorDirectoryController {

    private final PublicDoctorDirectoryService directoryService;

    public PublicDoctorDirectoryController(
            PublicDoctorDirectoryService directoryService
    ) {
        this.directoryService = directoryService;
    }

    @GetMapping("/doctors")
    public ResponseEntity<List<DoctorDirectoryEntry>> getDoctors() {
        return ResponseEntity.ok(directoryService.getDirectory());
    }
}
