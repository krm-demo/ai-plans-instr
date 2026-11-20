#!/usr/bin/env bash
# One-time AWS setup of plan-equilib: the Lambda, its public Function URL and the role GitHub Actions deploys with.
# The same steps were done for account 560574854768 in us-east-1 on 2026-11-20; this script is the record of them
# (and lets you repeat them in another account). Needs an AWS profile that may create IAM roles.
#
#   ./plan-equilib/deploy/setup.sh            # from the repo root, after: mvn -pl plan-equilib -am package
set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
FN=plan-equilib
REPO=krm-demo/ai-plans-instr
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
JAR=plan-equilib/target/plan-equilib.jar

# 1. The role the function runs with: only CloudWatch logs.
aws iam create-role --role-name plan-equilib-exec --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}'
aws iam attach-role-policy --role-name plan-equilib-exec \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
sleep 10   # a new role needs a few seconds before Lambda accepts it

# 2. The function: plain Java 21 on arm64 (the cheaper one), no libraries, the jar is the package.
#    (On 2026-11-20 it was created with a placeholder zip; the first GitHub Actions run deployed the jar.)
aws lambda create-function --region "$REGION" --function-name "$FN" \
  --runtime java21 --architectures arm64 --memory-size 512 --timeout 10 \
  --handler org.krmdemo.plansinstr.equilib.EquilibHandler::handleRequest \
  --role "arn:aws:iam::$ACCOUNT:role/plan-equilib-exec" \
  --zip-file "fileb://$JAR"
aws lambda wait function-active-v2 --region "$REGION" --function-name "$FN"

# 3. A public HTTPS address, no authentication (yet). Both permissions are needed for a Function URL.
aws lambda create-function-url-config --region "$REGION" --function-name "$FN" --auth-type NONE \
  --cors 'AllowOrigins=*,AllowMethods=GET'
aws lambda add-permission --region "$REGION" --function-name "$FN" --statement-id public-url \
  --action lambda:InvokeFunctionUrl --principal '*' --function-url-auth-type NONE
aws lambda add-permission --region "$REGION" --function-name "$FN" --statement-id public-url-invoke \
  --action lambda:InvokeFunction --principal '*' --invoked-via-function-url

# 3b. No authentication yet, so cap the cost of abuse: at most 5 copies at a time (more callers get 429).
aws lambda put-function-concurrency --region "$REGION" --function-name "$FN" --reserved-concurrent-executions 5

# 4. GitHub Actions of this repository (pushes to main) may replace the code of this one function, nothing else.
#    The identity provider token.actions.githubusercontent.com exists once per account; create it if missing.
if ! aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text \
     | grep -q token.actions.githubusercontent.com; then
  aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
    --client-id-list sts.amazonaws.com --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
fi
# GitHub may put immutable ids into the subject: repo:<owner>@<owner id>/<repo>@<repo id>:ref:… (it does for krm-demo).
SUB_REPO="krm-demo@$(gh api repos/$REPO --jq .owner.id)/${REPO#*/}@$(gh api repos/$REPO --jq .id)"
aws iam create-role --role-name plan-equilib-deploy --assume-role-policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Principal\": {\"Federated\": \"arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com\"},
    \"Action\": \"sts:AssumeRoleWithWebIdentity\",
    \"Condition\": {
      \"StringEquals\": {\"token.actions.githubusercontent.com:aud\": \"sts.amazonaws.com\",
                         \"token.actions.githubusercontent.com:sub\": [\"repo:$SUB_REPO:ref:refs/heads/main\",
                                                                     \"repo:$REPO:ref:refs/heads/main\"]}}}]}"
aws iam put-role-policy --role-name plan-equilib-deploy --policy-name update-plan-equilib --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Action\": [\"lambda:UpdateFunctionCode\", \"lambda:GetFunction\", \"lambda:GetFunctionConfiguration\", \"lambda:GetFunctionUrlConfig\"],
    \"Resource\": \"arn:aws:lambda:$REGION:$ACCOUNT:function:$FN\"}]}"

aws lambda get-function-url-config --region "$REGION" --function-name "$FN" --query FunctionUrl --output text
