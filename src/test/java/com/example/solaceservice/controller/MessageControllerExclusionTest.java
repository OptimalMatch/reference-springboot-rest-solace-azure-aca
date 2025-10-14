package com.example.solaceservice.controller;

import com.example.solaceservice.model.MessageRequest;
import com.example.solaceservice.service.MessageExclusionService;
import com.example.solaceservice.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MessageController.class)
class MessageControllerExclusionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MessageService messageService;

    @MockBean
    private MessageExclusionService exclusionService;

    @Test
    void shouldExcludeMessageWhenMatchesExclusionRule() throws Exception {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("{\"orderId\":\"TEST-BLOCKED-001\"}");
        request.setDestination("test/topic");
        request.setCorrelationId("test-001");
        
        when(exclusionService.shouldExclude(anyString(), any())).thenReturn(true);

        // When/Then
        mockMvc.perform(post("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())  // HTTP 202
            .andExpect(jsonPath("$.status").value("EXCLUDED"))
            .andExpect(jsonPath("$.messageId").exists())
            .andExpect(jsonPath("$.destination").value("test/topic"));
        
        // Verify message service was NOT called
        verify(messageService, never()).sendMessage(any(), anyString());
        verify(exclusionService, times(1)).shouldExclude(anyString(), any());
    }

    @Test
    void shouldProcessMessageWhenNotExcluded() throws Exception {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("{\"orderId\":\"ALLOWED-001\"}");
        request.setDestination("test/topic");
        request.setCorrelationId("test-002");
        
        when(exclusionService.shouldExclude(anyString(), any())).thenReturn(false);
        doNothing().when(messageService).sendMessage(any(), anyString());

        // When/Then
        mockMvc.perform(post("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())  // HTTP 200
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.messageId").exists())
            .andExpect(jsonPath("$.destination").value("test/topic"));
        
        // Verify message service WAS called
        verify(messageService, times(1)).sendMessage(any(), anyString());
        verify(exclusionService, times(1)).shouldExclude(anyString(), any());
    }

    @Test
    void shouldReturnFailedStatusWhenMessageServiceThrowsException() throws Exception {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("test message");
        request.setDestination("test/topic");
        
        when(exclusionService.shouldExclude(anyString(), any())).thenReturn(false);
        doThrow(new RuntimeException("Solace connection failed"))
            .when(messageService).sendMessage(any(), anyString());

        // When/Then
        mockMvc.perform(post("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isInternalServerError())  // HTTP 500
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.messageId").exists());
        
        verify(exclusionService, times(1)).shouldExclude(anyString(), any());
        verify(messageService, times(1)).sendMessage(any(), anyString());
    }

    @Test
    void shouldExcludeSWIFTMessageWithBlockedUETR() throws Exception {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("{1:F01BANKUS33AXXX}{4::121:blocked-uuid-123:23B:CRED-}");
        request.setDestination("swift/topic");
        
        when(exclusionService.shouldExclude(anyString(), any())).thenReturn(true);

        // When/Then
        mockMvc.perform(post("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("EXCLUDED"));
        
        verify(messageService, never()).sendMessage(any(), anyString());
    }

    @Test
    void shouldExcludeHL7MessageWithBlockedPatientId() throws Exception {
        // Given
        MessageRequest request = new MessageRequest();
        request.setContent("MSH|^~\\&|HIS|HOSPITAL||20251014||ADT^A01|MSG12345|P|2.5\rPID|1||BLOCKED-PATIENT-123");
        request.setDestination("hl7/topic");
        
        when(exclusionService.shouldExclude(anyString(), any())).thenReturn(true);

        // When/Then
        mockMvc.perform(post("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("EXCLUDED"));
        
        verify(messageService, never()).sendMessage(any(), anyString());
    }

    @Test
    void shouldHandleValidationErrorsBeforeExclusionCheck() throws Exception {
        // Given - Invalid request (missing required fields)
        MessageRequest request = new MessageRequest();
        // No content or destination set

        // When/Then
        mockMvc.perform(post("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
        
        // Exclusion check should not be called for invalid requests
        verify(exclusionService, never()).shouldExclude(anyString(), any());
        verify(messageService, never()).sendMessage(any(), anyString());
    }
}

