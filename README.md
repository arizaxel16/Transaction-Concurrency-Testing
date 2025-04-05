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

```java
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
Cómo Funciona:
El bloqueo pesimista opera bajo la premisa de que los conflictos son probables. Por lo tanto, antes de realizar cualquier operación sobre un recurso compartido (en este caso, las cuentas bancarias), la transacción adquiere un bloqueo exclusivo sobre dicho recurso a nivel de la base de datos (usualmente mediante sentencias como SELECT ... FOR UPDATE). Este bloqueo se mantiene durante toda la transacción.

##### Efectividad en Concurrencia:

Prevención Directa de Conflictos: Al bloquear el recurso (las filas de las cuentas originAccount y targetAccount), cualquier otra transacción que intente acceder a esas mismas filas con intención de modificarlas será detenida (bloqueada) hasta que la transacción original libere los bloqueos (al hacer commit o rollback).
Garantía de Integridad: Esto asegura que la secuencia "leer saldo -> verificar fondos -> restar saldo -> sumar saldo -> guardar saldos" ocurra de manera aislada para las cuentas involucradas, evitando que otra transacción interfiera a mitad de camino y cause inconsistencias.
Simplicidad Relativa: La lógica de manejo de concurrencia se delega en gran medida al sistema de gestión de base de datos.
Consideraciones:

##### Rendimiento: 

Puede reducir el throughput (transacciones por segundo) en escenarios de alta contención, ya que las transacciones pasan tiempo esperando la liberación de bloqueos.
Deadlocks: Es susceptible a deadlocks (bloqueos mutuos) si las transacciones adquieren bloqueos en órdenes diferentes, aunque esto se puede mitigar adquiriendo bloqueos en un orden consistente.

Resultados Cuantitativos (Pesimista):

* Tasa de éxito bajo carga simulada de [Número] usuarios concurrentes: 100%
* Throughput promedio (transferencias/segundo):
![Grafica ThroPessimistic](/Anexos/ThroPessimistic.png)
* Latencia promedio por transferencia exitosa: 3ms
Gráfica 1: Rendimiento del Bloqueo Pesimista vs. Carga Concurrente
![Grafica General](/Anexos/GraficaGeneral.jpg)

Aquí en este diagrama el es el 3 pico donde se la distribución de carga correcta entre BD y Java.


#### a. Bloqueo Optimista

El bloqueo optimista asume que los conflictos son poco frecuentes. Las transacciones leen los datos sin adquirir bloqueos iniciales. Para detectar conflictos, se utiliza un mecanismo de versionamiento (a menudo un campo @Version en la entidad JPA/Hibernate).

Una transacción lee los datos, incluyendo su número de versión actual.
Realiza las operaciones (restar/sumar saldo) en memoria.
Al intentar confirmar (guardar los cambios), verifica si el número de versión de los datos en la base de datos sigue siendo el mismo que cuando se leyeron.

Si la versión coincide, la actualización se realiza y el número de versión se incrementa.
Si la versión no coincide (significa que otra transacción modificó los datos mientras tanto), la operación de guardado falla (lanzando una OptimisticLockingFailureException).
Efectividad en Concurrencia:

Detección de Conflictos: Detecta eficazmente si ha ocurrido una modificación concurrente justo antes de confirmar los cambios.
Alto Rendimiento en Baja Contención: Como no hay bloqueos iniciales, permite un alto grado de paralelismo cuando los conflictos son raros, resultando en buen throughput.
Mecanismo de Reintento: Combinado con una estrategia de reintento (@Retryable), las fallas por bloqueo optimista pueden manejarse automáticamente, reintentando la transacción completa. Esto permite que la operación eventualmente tenga éxito si el conflicto fue transitorio.
Consideraciones:

Trabajo Desperdiciado: Si un conflicto ocurre y la transacción falla, todo el trabajo realizado en esa transacción (hasta el punto de la falla) se desperdicia y debe repetirse.
Degradación en Alta Contención: En escenarios de muy alta contención sobre los mismos recursos, la tasa de fallos y reintentos puede aumentar significativamente, degradando el rendimiento e incluso superando la sobrecarga del bloqueo pesimista.
Complejidad de Reintentos: La lógica de reintentos debe manejar adecuadamente la propagación de transacciones (Propagation.REQUIRES_NEW) para asegurar que cada intento sea atómico.
Resultados Cuantitativos (Optimista con Reintentos):

Tasa de éxito bajo carga simulada de [Número] usuarios concurrentes (considerando reintentos exitosos): 99.81%
Throughput promedio (transferencias/segundo): 5
Número promedio de reintentos por transacción conflictiva: 3

![Grafica General](/Anexos/GraficaGeneral.jpg)

es el 2 pico donde se ve una distribución de cargas mós o menos.
Ultimos resultados obtenidos: 
Optimistic Lock Transaction Test Completed.
Total Transfers Attempted: 10000
Total Transfers (Successfully Recorded): 9996
Final Balance of Account abc: 4.0000
Final Balance of Account cbd: 19996.0000

### Conclusión Parcial:

Tanto el bloqueo pesimista como el optimista ofrecen mecanismos robustos para mantener la consistencia en entornos concurrentes. La elección entre uno y otro a menudo depende de la naturaleza de la aplicación y la frecuencia esperada de conflictos:

* El Bloqueo Pesimista es generalmente preferible cuando la contención es alta y la prioridad es garantizar la ejecución sin fallos por conflicto (a costa de un posible menor paralelismo).
* El Bloqueo Optimista tiende a ser más eficiente cuando la contención es baja o moderada, permitiendo mayor paralelismo, pero requiere una gestión cuidadosa de los reintentos ante fallos.

