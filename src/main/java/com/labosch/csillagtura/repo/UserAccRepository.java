package com.labosch.csillagtura.repo;

import com.labosch.csillagtura.entity.UserAcc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccRepository extends JpaRepository<UserAcc, Long> {
    Optional<UserAcc> findById(long id);
}
