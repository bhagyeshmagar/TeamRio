#!/bin/bash
pkill -f "spring-boot:run"

# Function to start service
start_service() {
    service_name=$1
    folder=$2
    log_file="/tmp/$service_name.log"
    echo "Starting $service_name in $folder..."
    (cd $folder && mvn spring-boot:run -Dspring-boot.run.profiles=dev,ai > $log_file 2>&1) &
    echo $! > "/tmp/$service_name.pid"
}

# 1. Start Main Backend
# start_service "log-analyzer" "../log-analyzer-service"

# 2. Start Demo Services
start_service "api-gateway" "api-gateway"
start_service "user-service" "user-service"
start_service "order-service" "order-service"
start_service "inventory-service" "inventory-service"
start_service "payment-service" "payment-service"

echo "All services starting. Tail logs with: tail -f /tmp/*.log"
wait
