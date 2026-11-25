package studio.foliarc.biz.run.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.run.dto.RunDtos.InternalRunEventInput;
import studio.foliarc.biz.run.entity.AgentRunEventEntity;
import studio.foliarc.biz.run.mapper.AgentRunEventMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Service
public class AgentRunEventService {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final ObjectReader payloadReader;

    public AgentRunEventService(AgentRunMapper runMapper, AgentRunEventMapper eventMapper, ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.payloadReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Transactional
    public void saveEvents(UUID runId, List<InternalRunEventInput> events) {
        if (events == null || events.isEmpty() || runMapper.lockForEventInsert(runId) == null) {
            return;
        }
        for (InternalRunEventInput input : events) {
            if (input == null || input.eventType() == null || input.eventType().isBlank()
                    || "text_delta".equalsIgnoreCase(input.eventType().strip())) {
                continue;
            }
            AgentRunEventEntity event = new AgentRunEventEntity();
            event.setId(UUID.randomUUID());
            event.setRunId(runId);
            event.setSeq(Objects.requireNonNullElse(input.seq(), 0));
            event.setEventType(input.eventType().strip());
            event.setAgentName(valueOrDefault(input.agentName(), "writer"));
            event.setTraceId(valueOrDefault(input.traceId(), ""));
            event.setLatencyMs(Objects.requireNonNullElse(input.latencyMs(), 0));
            event.setPayloadJson(validatedPayload(input.payloadJson()));
            eventMapper.insert(event);
        }
    }

    private String validatedPayload(String json) {
        String payload = valueOrDefault(json, "{}");
        try {
            payloadReader.readTree(payload);
            return payload;
        } catch (JacksonException ex) {
            throw BizException.badRequest("INVALID_RUN_EVENT_PAYLOAD", "payload_json 必须为合法 JSON");
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
