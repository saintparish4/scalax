#!/bin/bash
# teardown-demo.sh - Destroy everything

cd terraform/environments/demo || exit
terraform destroy -auto-approve

echo "Demo environment destroyed"
echo "Total demo cost: ~\$2-5 depending on duration"