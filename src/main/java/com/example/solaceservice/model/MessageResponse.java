package com.example.solaceservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class MessageResponse {
    private String messageId;
    private String status;
    private String destination;
    private LocalDateTime timestamp;
}