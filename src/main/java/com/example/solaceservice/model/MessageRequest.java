package com.example.solaceservice.model;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class MessageRequest {
    @NotBlank(message = "Message content cannot be blank")
    private String content;

    private String destination;

    private String correlationId;
}