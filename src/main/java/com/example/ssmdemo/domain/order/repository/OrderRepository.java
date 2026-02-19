package com.example.ssmdemo.domain.order.repository;

import com.example.ssmdemo.domain.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {
}
