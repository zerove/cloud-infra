#!/bin/bash
for file in manifests/base/*.yaml; do
    sed -i 's/imagePullPolicy: Always/imagePullPolicy: IfNotPresent/g' "$file"
    sed -i '/imagePullPolicy: IfNotPresent/!b; n; /image:/a\          imagePullPolicy: IfNotPresent' "$file"
    kubectl apply -f "$file"
done
