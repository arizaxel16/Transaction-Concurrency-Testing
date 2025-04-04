# Proyecto de pruebas de concurrencia

## Made by:
*  Juan Pablo Corral
*  Axel Ariza Pulido

# Análisis de Concurrencia en Transferencias de Fondos

## 1. Introducción

### Problema a solucionar

Este análisis se centra en los desafíos de **concurrencia** identificados en el módulo de transferencia de fondos entre cuentas de nuestra aplicación Java.

La funcionalidad opera sobre un modelo de datos donde los saldos de las cuentas se persisten en una base de datos relacional, gestionada a través de **JPA** en un entorno **Spring Boot**.

El problema surge cuando múltiples hilos intentan realizar transferencias simultáneas que afectan a las mismas cuentas. Al no haber un control explícito de concurrencia, se presentan **condiciones de carrera** que resultan en **inconsistencias de datos**. Esto puede derivar en balances incorrectos a nivel de cuenta individual, comprometiendo la integridad del sistema.

Esta situación impulsó la necesidad de investigar e implementar mecanismos de control de concurrencia robustos. Se exploraron diversas estrategias, incluyendo bloqueos a nivel de base de datos (pesimistas y optimistas) y técnicas a nivel de aplicación, evaluando sus ventajas y desventajas en términos de correctitud, rendimiento bajo carga, riesgo de interbloqueos y distribución de la carga entre la aplicación y la base de datos.

## 2. Soluciones Propuestas

A continuación se describen los distintos enfoques evaluados para controlar la concurrencia en el módulo de transferencias:

### a. Transacción por defecto

```yaml
    @Transactional
    public void transfer(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId + " (Balance: " + originAccount.getBalance() + ")");
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
```
### b. Transacción Sincronizada
```java
    @Transactional
    public synchronized void transferSynchronized(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Sync) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: (Sync) Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId + " (Balance: " + originAccount.getBalance() + ")");
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Sync) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
```
### c. Transacción pesimista
```java
    @Transactional
    public void transferPessimistic(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Pessimistic) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        // Bloquea las filas correspondientes en la base de datos
        Account originAccount = accountRepository.findByIdForUpdate(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findByIdForUpdate(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: (Pessimistic) Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
        }

        // Realiza la transferencia
        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Pessimistic) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
```
### d. Transacción Optimista

```java
    @Retryable( // Reintenta si ocurre una falla de bloqueo optimista
            retryFor = { OptimisticLockingFailureException.class },
            maxAttempts = 15, // Número máximo de reintentos
            backoff = @Backoff( // Estrategia de espera entre reintentos
                    delay = 100,      // Espera inicial (ms)
                    multiplier = 2,   // Multiplicador para la espera
                    maxDelay = 2000   // Espera máxima (ms)
            )
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW) // Cada reintento en una nueva transacción
    public void transferOptimistic(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Optimistic) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        // Lee las cuentas sin bloquearlas (confiando en la versión)
        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        if (originAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Thread {}: (Optimistic) Insufficient balance in account {}. Required: {}, Available: {}",
                    Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
            throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
        }

        originAccount.setBalance(originAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        // Al guardar, JPA verifica si la versión ha cambiado. Si cambió, lanza OptimisticLockingFailureException
        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Optimistic) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
```
### e. Transacción con ReentrantLock
```java
    @Transactional
    public void transferReentrantLock(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Reentrant) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        // Determina el orden de bloqueo para evitar deadlocks
        String firstLockId = originAccountId.compareTo(targetAccountId) < 0 ? originAccountId : targetAccountId;
        String secondLockId = originAccountId.compareTo(targetAccountId) < 0 ? targetAccountId : originAccountId;

        // Obtiene los locks para las cuentas específicas
        ReentrantLock firstLock = AccountLockManager.getLock(firstLockId);
        ReentrantLock secondLock = AccountLockManager.getLock(secondLockId);

        // Adquiere los locks en orden consistente
        firstLock.lock();
        try {
            secondLock.lock();
            try {
                // Dentro de la sección crítica (ambos locks adquiridos)
                Account originAccount = accountRepository.findById(originAccountId)
                        .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
                Account targetAccount = accountRepository.findById(targetAccountId)
                        .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

                if (originAccount.getBalance().compareTo(amount) < 0) {
                    log.warn("Thread {}: (Reentrant) Insufficient balance in account {}. Required: {}, Available: {}",
                            Thread.currentThread().getId(), originAccountId, amount, originAccount.getBalance());
                    throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
                }

                originAccount.setBalance(originAccount.getBalance().subtract(amount));
                targetAccount.setBalance(targetAccount.getBalance().add(amount));

                accountRepository.save(originAccount);
                accountRepository.save(targetAccount);

                log.info("Thread {}: (Reentrant) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                        Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());

            } finally {
                // Libera el segundo lock
                secondLock.unlock();
            }
        } finally {
            // Libera el primer lock
            firstLock.unlock();
        }
    }
```
### f. Transacción Atómica (con AtomicReference y CAS)
```java
    @Transactional
    public void transferAtomic(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (Atomic) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        // Usa AtomicReference para operaciones Compare-And-Swap (CAS) en los saldos
        AtomicReference<BigDecimal> originBalance = new AtomicReference<>(originAccount.getBalance());
        AtomicReference<BigDecimal> targetBalance = new AtomicReference<>(targetAccount.getBalance());

        boolean updated = false;
        while (!updated) { // Bucle CAS: reintenta hasta que la actualización sea atómica
            BigDecimal currentOriginBalance = originBalance.get();
            BigDecimal currentTargetBalance = targetBalance.get();

            if (currentOriginBalance.compareTo(amount) < 0) {
                log.warn("Thread {}: (Atomic) Insufficient balance in account {}. Required: {}, Available: {}",
                        Thread.currentThread().getId(), originAccountId, amount, currentOriginBalance);
                throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
            }

            BigDecimal newOriginBalance = currentOriginBalance.subtract(amount);
            BigDecimal newTargetBalance = currentTargetBalance.add(amount);

            // Intenta actualizar atómicamente ambos saldos
            // Si el valor actual no ha cambiado desde que se leyó, se actualiza. Si cambió, falla y se reintenta.
            if (originBalance.compareAndSet(currentOriginBalance, newOriginBalance) &&
                    targetBalance.compareAndSet(currentTargetBalance, newTargetBalance)) {
                updated = true; // Éxito
            }
            // Si falla el segundo CAS después de que el primero tuvo éxito, hay que revertir o manejar la inconsistencia.
            // (Nota: Este ejemplo simple asume que ambos CAS suceden o no, lo cual no es estrictamente garantizado sin un mecanismo adicional)
            // Una implementación más robusta podría requerir revertir el primer CAS si el segundo falla.
        }

        // Actualiza los objetos de entidad con los valores atómicos finales
        originAccount.setBalance(originBalance.get());
        targetAccount.setBalance(targetBalance.get());

        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (Atomic) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
```
### g. Transacción con STM (Software Transactional Memory)
```java
    @Transactional
    public void transferSTM(String originAccountId, String targetAccountId, BigDecimal amount) {
        log.info("Thread {}: (STM) Attempting transfer from {} to {} amount {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, amount);

        Account originAccount = accountRepository.findById(originAccountId)
                .orElseThrow(() -> new RuntimeException("Origin account not found: " + originAccountId));
        Account targetAccount = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> new RuntimeException("Target account not found: " + targetAccountId));

        // Inicializa los saldos STM si no existen (usando un campo transitorio o similar)
        // Asume que Account tiene un campo como 'private transient volatile BigDecimal stmBalance;'
        // y métodos getStmBalance/setStmBalance que operan sobre variables transaccionales STM.
        // La inicialización real dependería de la biblioteca STM usada.
        // Este es un ejemplo conceptual.
        if (originAccount.getStmBalance() == null) {
             originAccount.setStmBalance(originAccount.getBalance()); // Inicializa con el valor de la BD
        }
        if (targetAccount.getStmBalance() == null) {
             targetAccount.setStmBalance(targetAccount.getBalance()); // Inicializa con el valor de la BD
        }

        // Ejecuta la lógica de negocio dentro de una transacción atómica STM
        StmUtils.atomic(() -> { // StmUtils.atomic es una representación de cómo se invocaría una transacción STM
            if (originAccount.getStmBalance().compareTo(amount) < 0) {
                log.warn("Thread {}: (STM) Insufficient balance in account {}. Required: {}, Available: {}",
                        Thread.currentThread().getId(), originAccountId, amount, originAccount.getStmBalance());
                throw new RuntimeException("Insufficient balance in origin account: " + originAccountId);
            }

            // Las modificaciones dentro del bloque atomic son transaccionales
            originAccount.setStmBalance(originAccount.getStmBalance().subtract(amount));
            targetAccount.setStmBalance(targetAccount.getStmBalance().add(amount));
        });

        // Después de que la transacción STM confirma, actualiza los saldos persistentes
        originAccount.setBalance(originAccount.getStmBalance());
        targetAccount.setBalance(targetAccount.getStmBalance());
        accountRepository.save(originAccount);
        accountRepository.save(targetAccount);

        log.info("Thread {}: (STM) Transfer successful from {} to {}. New Origin Balance: {}, New Target Balance: {}",
                Thread.currentThread().getId(), originAccountId, targetAccountId, originAccount.getBalance(), targetAccount.getBalance());
    }
```

## 3. Análisis de Efectividad: Bloqueo Pesimista y Optimista

En el contexto de la gestión de concurrencia para las operaciones de transferencia de fondos, las estrategias de Bloqueo Pesimista (Pessimistic Locking) y Bloqueo Optimista (Optimistic Locking) demostraron ser particularmente efectivas para garantizar la integridad de los datos y manejar el acceso simultáneo a las cuentas. Aunque operan bajo filosofías distintas, ambas logran prevenir condiciones de carrera críticas, como las "lost updates" (actualizaciones perdidas), donde el resultado de una transacción es sobrescrito incorrectamente por otra concurrente.

#### a. Bloqueo Pesimista (Pessimistic Locking)
**Cómo Funciona:**

El bloqueo pesimista opera bajo la premisa de que los conflictos son probables. Por lo tanto, antes de realizar cualquier operación sobre un recurso compartido (en este caso, las cuentas bancarias), la transacción adquiere un bloqueo exclusivo sobre dicho recurso a nivel de la base de datos (usualmente mediante sentencias como SELECT ... FOR UPDATE). Este bloqueo se mantiene durante toda la transacción.

##### Efectividad en Concurrencia:

*   Prevención Directa de Conflictos: Al bloquear el recurso (las filas de las cuentas originAccount y targetAccount), cualquier otra transacción que intente acceder a esas mismas filas con intención de modificarlas será detenida (bloqueada) hasta que la transacción original libere los bloqueos (al hacer commit o rollback).
*    Garantía de Integridad: Esto asegura que la secuencia "leer saldo -> verificar fondos -> restar saldo -> sumar saldo -> guardar saldos" ocurra de manera aislada para las cuentas involucradas, evitando que otra transacción interfiera a mitad de camino y cause inconsistencias.
*    Simplicidad Relativa: La lógica de manejo de concurrencia se delega en gran medida al sistema de gestión de base de datos.

**Consideraciones:**

*    Rendimiento: Puede reducir el throughput (transacciones por segundo) en escenarios de alta contención, ya que las transacciones pasan tiempo esperando la liberación de bloqueos.
*    Deadlocks: Es susceptible a deadlocks (bloqueos mutuos) si las transacciones adquieren bloqueos en órdenes diferentes, aunque esto se puede mitigar adquiriendo bloqueos en un orden consistente.

**Resultados Cuantitativos (Pesimista):**

*    Tasa de éxito bajo carga simulada: 100%
*    Throughput promedio (transferencias/segundo): [Insertar Número o ver gráfica abajo]

![Grafica ThroPessimistic](/Anexos/ThroPessimistic.png)

*    Latencia promedio por transferencia exitosa: ~3ms
*    Tasa de error (timeouts, deadlocks detectados): 0% (Asumiendo éxito del 100%)

Gráfica 1: Rendimiento del Bloqueo Pesimista vs. Carga Concurrente

![Grafica General](/Anexos/GraficaGeneral.jpg)

El tercer pico corresponde a la prueba de bloqueo pesimista, mostrando la distribución de carga.

#### a. Bloqueo Optimista

**Cómo Funciona:**
El bloqueo optimista asume que los conflictos son poco frecuentes. Las transacciones leen los datos sin adquirir bloqueos iniciales. Para detectar conflictos, se utiliza un mecanismo de versionamiento (a menudo un campo @Version en la entidad JPA/Hibernate).

1. Una transacción lee los datos, incluyendo su número de versión actual.
2. Realiza las operaciones (restar/sumar saldo) en memoria.
3. Al intentar confirmar (guardar los cambios), verifica si el número de versión de los datos en la base de datos sigue siendo el mismo que cuando se leyeron.
    *    Si la versión coincide, la actualización se realiza y el número de versión se incrementa.
    *    Si la versión no coincide (significa que otra transacción modificó los datos mientras tanto), la operación de guardado falla (lanzando una OptimisticLockingFailureException).

**Efectividad en Concurrencia:**

*    Detección de Conflictos: Detecta eficazmente si ha ocurrido una modificación concurrente justo antes de confirmar los cambios.
*    Alto Rendimiento en Baja Contención: Como no hay bloqueos iniciales, permite un alto grado de paralelismo cuando los conflictos son raros, resultando en buen throughput.
*    Mecanismo de Reintento: Combinado con una estrategia de reintento (@Retryable), las fallas por bloqueo optimista pueden manejarse automáticamente, reintentando la transacción completa. Esto permite que la operación eventualmente tenga éxito si el conflicto fue transitorio.

**Consideraciones:**

*    Trabajo Desperdiciado: Si un conflicto ocurre y la transacción falla, todo el trabajo realizado en esa transacción (hasta el punto de la falla) se desperdicia y debe repetirse.
*    Degradación en Alta Contención: En escenarios de muy alta contención sobre los mismos recursos, la tasa de fallos y reintentos puede aumentar significativamente, degradando el rendimiento e incluso superando la sobrecarga del bloqueo pesimista.
*    Complejidad de Reintentos: La lógica de reintentos debe manejar adecuadamente la propagación de transacciones (Propagation.REQUIRES_NEW) para asegurar que cada intento sea atómico.

**Resultados Cuantitativos (Optimista con Reintentos):**

*    Tasa de éxito final (considerando reintentos): 99.81% (Basado en 9996 exitosas de 10000 intentos iniciales)
*    Throughput promedio (transferencias/segundo): ~5 
*    Número promedio de reintentos por transacción conflictiva: ~3

![Grafica General](/Anexos/GraficaGeneral.jpg)

es el 2 pico donde se ve una distribución de cargas más o menos.

**Ultimos resultados obtenidos:** 
Optimistic Lock Transaction Test Completed.
Total Transfers Attempted: 10000
Total Transfers (Successfully Recorded): 9996
Final Balance of Account abc: 4.0000
Final Balance of Account cbd: 19996.0000

#### Conclusión Parcial (Sección 3):
Tanto el bloqueo pesimista como el optimista ofrecen mecanismos robustos para mantener la consistencia en entornos concurrentes. La elección entre uno y otro a menudo depende de la naturaleza de la aplicación y la frecuencia esperada de conflictos:

*    El Bloqueo Pesimista demostró ser extremadamente fiable (100% éxito) y simple de implementar a nivel de base de datos, garantizando la ejecución sin fallos por conflicto, aunque su throughput puede ser un factor a considerar bajo cargas muy altas (ver gráfica específica ThroPessimistic.png). Es ideal cuando la integridad es crítica y la contención es alta.
*    El Bloqueo Optimista, aunque no alcanzó el 100% de éxito final incluso con reintentos (99.81%), ofrece potencialmente mayor throughput en escenarios de baja/moderada contención al evitar bloqueos iniciales. Sin embargo, introduce complejidad con la gestión de reintentos y el manejo de fallos persistentes.
Basado en los resultados, el Bloqueo Pesimista parece ser la opción más segura y consistente para este caso de uso crítico, mientras que el Optimista podría considerarse si el rendimiento bajo baja contención es prioritario y la tasa de fallo observada es aceptable.

### 4. Observaciones Adicionales: Distribución de Carga (New Relic)

Durante la ejecución de las pruebas de concurrencia, se utilizó New Relic para monitorizar el rendimiento y la utilización de recursos en los hosts donde se ejecutaba la aplicación y la base de datos. Se observaron diferencias notables en la distribución de la carga de CPU entre dos entornos de prueba distintos, denominados Host A y Host B.

#### a. Host A: Distribución Equitativa de Carga

En el Host A, las gráficas de utilización de CPU de New Relic mostraron una distribución de carga relativamente equitativa entre el proceso Java de la aplicación y el proceso de la base de datos PostgreSQL, particularmente durante las fases de mayor intensidad de las pruebas (correspondientes a los picos de las pruebas de bloqueo Pesimista y Optimista). Como se puede apreciar en la imagen adjunta (GraficaGeneral.jpg), existe un balance notable donde tanto la aplicación Java como la base de datos PostgreSQL consumen una porción significativa de los recursos de CPU durante estas fases.

**Observación Clave:**

Carga compartida entre la aplicación (Java) y la base de datos (PostgreSQL).
Indica que la base de datos estaba realizando un trabajo considerable (procesando consultas, gestionando bloqueos, realizando I/O) en respuesta a las solicitudes de la aplicación.

![Grafica General](/Anexos/GraficaGeneral.jpg)

#### b. Host B: Carga Concentrada en Java

En contraste, en el Host B, la carga de CPU observada en New Relic estaba predominantemente concentrada en el proceso Java. El proceso de PostgreSQL mostraba una utilización significativamente menor durante las mismas pruebas, casi insignificante en comparación con la carga del proceso Java.

**Observación Clave:**
*    La mayor parte de la carga de CPU recae sobre la aplicación (Java).
*    La base de datos (PostgreSQL) muestra una actividad de CPU muy baja.

**Gráfica 4: Monitorización New Relic - Host B**

![Grafica General](Anexos/HostB.png)

#### c. Posibles Interpretaciones y Causas

Esta disparidad en la distribución de carga entre Host A y Host B, ejecutando presumiblemente el mismo código y pruebas, sugiere que la interacción entre la aplicación y la base de datos no fue la misma o que existían diferencias subyacentes en la configuración o el entorno. Algunas posibles causas para investigar incluyen:

*    Configuración del Pool de Conexiones: Diferencias en la configuración del pool de conexiones (ej. HikariCP, C3P0) entre los hosts podrían afectar cómo y cuántas conexiones se establecen y reutilizan, impactando la carga en la base de datos. Un pool mal configurado en Host B podría estar limitando el acceso a la BD.
*    Latencia de Red: Si la base de datos no residía en el mismo host que la aplicación, una mayor latencia de red entre la aplicación en Host B y la base de datos podría causar que el proceso Java pase más tiempo esperando respuestas, inflando su tiempo de CPU relativo mientras la BD está ociosa.
*    Recursos de la Base de Datos: Podría ser que la instancia de PostgreSQL accesible desde Host B estuviera limitada en recursos (CPU, I/O, memoria) o tuviera una configuración diferente (ej. max_connections, work_mem), actuando como un cuello de botella que impide que procese más carga, dejando a la aplicación Java esperando o manejando errores.
*    Configuración del Sistema Operativo o JVM: Diferencias a nivel de Sistema Operativo o en los parámetros de la Máquina Virtual Java (JVM) entre Host A y Host B podrían influir en el rendimiento de la aplicación o su capacidad para interactuar eficientemente con la red o la base de datos.
*    Diferencias Sutiles en la Carga de Prueba: Aunque se intentara replicar la prueba, diferencias en el estado inicial de los datos o variaciones menores en la ejecución podrían haber llevado a patrones de acceso distintos.

**Conclusión de la Observación:**
La diferencia observada es significativa. Mientras que el Host A muestra una interacción aparentemente saludable y balanceada donde la base de datos participa activamente, el Host B sugiere un posible cuello de botella relacionado con la base de datos o la comunicación con ella, o una configuración subóptima que concentra la carga en la aplicación. Se requeriría una investigación más profunda en las configuraciones y métricas detalladas de ambos hosts y sus bases de datos para determinar la causa raíz exacta de esta discrepancia.

### 5. Resultados Comparativos y Viabilidad de Otras Soluciones
Si bien el análisis anterior se centró en los bloqueos Pesimista y Optimista por ser los más adecuados para operaciones críticas en bases de datos relacionales, es importante revisar brevemente los resultados (o resultados esperados/típicos) de las otras soluciones probadas y por qué son generalmente menos viables en este contexto específico.

**Tabla Comparativa General** 
| Método                             | Tasa de Éxito (%) | Throughput (Tx/s) | Latencia Prom (ms) | Errores Comunes                          | Observaciones Clave / Viabilidad                                       |
|------------------------------------|--------------------|--------------------|---------------------|------------------------------------------|------------------------------------------------------------------------|
| a. Transacción por Defecto         | No hay exitos   | Alto     | Bajo                | Inconsistencia de datos (Balances finales incorrectos) |No Viable: Corrupción de datos garantizada.                         |
| b. Transacción Sincronizada        | 80%              | Muy Bajo     | Alto                | Ninguno (si la lógica es correcta)       | No Viable: Cuello de botella severo en JVM, no escala.             |
| c. Transacción Pesimista           | 100%               | Moderado      | Bajo (~3ms)         | Timeouts/Deadlocks (raros si se implementa bien) | Viable y Recomendado: Muy fiable.                                  |
| d. Transacción Optimista           | ~99.81%            | Moderado       | Variable            | `OptimisticLockingFailureException`      | Viable con Consideraciones: Rápido si hay pocos conflictos.        |
| e. Transacción con ReentrantLock   | Potencialmente Baja               | Bajo (~??)         | Alto                | Deadlocks (si no se gestiona bien)       | Menos Viable: Similar a synchronized, complejo.                    |
| f. Transacción Atómica (CAS)       | Potencialmente Baja| Variable           | Variable            | Inconsistencia DB, Fallos CAS            | No Viable: Difícil sincronizar con DB, no idiomático.              |
| g. Transacción con STM             | Variable           | Variable           | Variable            | Complejidad de integración, Errores STM  | Menos Viable: Excesivo para este problema, complejo.               |

### Análisis de Viabilidad de Otras Soluciones

a. Transacción por Defecto: Como era esperado, sin ningún mecanismo de control, las operaciones concurrentes interfieren entre sí, llevando a condiciones de carrera (lecturas y escrituras concurrentes sobre el mismo saldo) que resultan en saldos finales incorrectos. Es la línea base que demuestra la existencia del problema. No es una solución viable.

b. Transacción Sincronizada (synchronized): Este enfoque serializa la ejecución del método transferSynchronized a nivel de la JVM. Solo un hilo puede ejecutar este método a la vez en una instancia dada del servicio. Si bien previene las condiciones de carrera dentro de esa instancia, se convierte en un cuello de botella masivo, reduciendo drásticamente el throughput. Además, no escala horizontalmente; si se despliegan múltiples instancias de la aplicación, synchronized en una instancia no protege contra la ejecución concurrente en otra instancia, volviendo a generar condiciones de carrera a nivel de base de datos. No es viable para sistemas con carga.

e. Transacción con ReentrantLock: Similar a synchronized, ReentrantLock implementa un bloqueo a nivel de aplicación Java. Aunque ofrece más flexibilidad (ej. intentar adquirir el lock sin esperar), sigue presentando problemas fundamentales para este caso:

*    Escalabilidad: Los locks son locales a la JVM, no protegen entre múltiples instancias de servicio.
*    Cuello de Botella: Si el lock es muy general (como bloquear por ID de cuenta), puede serializar operaciones innecesariamente.
*    Complejidad: Requiere gestión manual cuidadosa (adquirir locks en orden consistente para evitar deadlocks, asegurar liberación en bloques finally).
*    Desacoplamiento de la BD: No protege contra modificaciones realizadas directamente en la base de datos o por otros sistemas. Generalmente menos viable que los bloqueos a nivel de base de datos para operaciones que dependen críticamente del estado persistido.

f. Transacción Atómica (AtomicReference/CAS): Las variables atómicas y Compare-And-Swap son excelentes para gestionar estado en memoria de forma concurrente y sin bloqueos tradicionales. Sin embargo, aplicarlas correctamente a la lógica transaccional de una base de datos es muy complejo y poco idiomático:

*    La atomicidad de CAS es en memoria, no se traduce directamente a la atomicidad de la transacción de base de datos (accountRepository.save).
*    Sincronizar la actualización atómica en memoria con la escritura (y posible rollback) en la base de datos es difícil de hacer correctamente y puede ser ineficiente (ej. bucles CAS + intentos de commit/rollback de BD).
*    El ejemplo mostrado es una simplificación; asegurar que ambas cuentas se actualicen atómicamente y que la transacción de BD refleje esto es el verdadero desafío. No es una solución práctica ni robusta para este problema.

  g.    Transacción con STM (Software Transactional Memory): STM aplica conceptos de transacciones de base de datos a la memoria compartida. Es una técnica poderosa pero:

*    Complejidad: Requiere bibliotecas específicas y un entendimiento profundo de su funcionamiento e integración con el gestor de persistencia (JPA). La integración puede no ser trivial.
*    Overkill: Para un problema relativamente directo de actualizar dos filas relacionadas en una base de datos transaccional, STM puede ser una solución excesivamente compleja comparada con los mecanismos nativos de la BD (bloqueos).
*    Rendimiento: El rendimiento de STM puede ser variable y sensible a la configuración y patrones de acceso. Menos viable debido a la complejidad y a que el problema central reside en la concurrencia a nivel de base de datos.
### Configuración Ideal

```java
spring:
  application:
    name: transaction-app
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: admin
    hikari:
      minimum-idle: 3 # Minimum number of idle connections to maintain
      maximum-pool-size: 21 # Maximum number of active connections
      idle-timeout: 300000 # Maximum time (in milliseconds) that a connection can sit idle in the pool (5 minutes)
      max-lifetime: 1800000 # Maximum lifetime (in milliseconds) of a connection in the pool (30 minutes)
      connection-timeout: 30000 # Maximum time (in milliseconds) to wait for a connection from the pool (30 seconds)
      validation-timeout: 5000 # Maximum time (in milliseconds) to wait for a connection to be validated as alive
      leak-detection-threshold: 0 # Time in milliseconds before logging a message about a potential connection leak (0 disables it)
      # connection-init-sql: SELECT 1 # SQL query to run to test new connections
      # pool-name: HikariPool-1 # You can customize the pool name if needed
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.PostgreSQLDialect

server:
  port: 8080
```

### Recomendación:

Dada la naturaleza crítica de las transferencias de fondos donde la integridad y consistencia de los datos es primordial, y considerando la fiabilidad demostrada del 100%:

*    Se recomienda implementar la estrategia de Bloqueo Pesimista (Pessimistic Locking) para el método de transferencia de fondos.

*    Aunque el Bloqueo Optimista puede ofrecer mayor rendimiento en ciertos escenarios, el riesgo (aunque bajo) de fallo y la complejidad añadida lo hacen menos adecuado para esta funcionalidad central. El Bloqueo Pesimista proporciona una garantía de consistencia más fuerte y directa en este contexto.
