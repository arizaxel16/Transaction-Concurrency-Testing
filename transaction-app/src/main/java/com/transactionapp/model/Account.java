package com.transactionapp.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
@Data
public class Account {
    @Id
    @Column(name = "account_id")
    private String accountId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal balance;
}