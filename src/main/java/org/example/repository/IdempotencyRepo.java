package org.example.repository;

import org.example.model.entity.IdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRepo extends JpaRepository<IdempotencyEntity, Long> {
    Optional<IdempotencyEntity> findByIdemKey(String idemKey);
}
