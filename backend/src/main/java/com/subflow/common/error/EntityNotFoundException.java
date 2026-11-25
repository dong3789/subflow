package com.subflow.common.error;

import org.springframework.http.HttpStatus;

/** 대상 리소스를 찾지 못했을 때(404). */
public class EntityNotFoundException extends BusinessException {

    public EntityNotFoundException(String entityName, Object identifier) {
        super("ENTITY_NOT_FOUND", entityName + "을(를) 찾을 수 없습니다: " + identifier, HttpStatus.NOT_FOUND);
    }

    public EntityNotFoundException(String message) {
        super("ENTITY_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
