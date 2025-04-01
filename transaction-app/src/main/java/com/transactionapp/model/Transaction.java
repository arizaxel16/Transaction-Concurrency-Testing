package com.transactionapp.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction")
@Data
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "origin_account_id", referencedColumnName = "account_id", nullable = false)
    private Account originAccount;

    @ManyToOne
    @JoinColumn(name = "target_account_id", referencedColumnName = "account_id", nullable = false)
    private Account targetAccount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    private LocalDateTime timestamp;
}