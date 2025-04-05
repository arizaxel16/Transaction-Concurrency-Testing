package com.transactionapp.model;

import jakarta.persistence.*;
import lombok.Data;
import org.multiverse.api.references.TxnRef;
import static org.multiverse.api.StmUtils.*;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
@Data
public class Account {
    @Id
    @Column(name = "account_id")
    private String accountId;

    @Transient // Don't persist in DB, only for STM transactions
    private TxnRef<BigDecimal> stmBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    @Column(nullable = false)
    private Long version;

    public Account() {
        this.stmBalance = newTxnRef(BigDecimal.ZERO); // Initialize STM balance
    }

    public void setStmBalance(BigDecimal newBalance) {
        this.stmBalance.set(newBalance);
    }

    public BigDecimal getStmBalance() {
        return this.stmBalance.get();
    }
}
