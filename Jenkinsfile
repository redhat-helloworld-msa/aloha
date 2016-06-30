node {

    properties([
        [$class: 'ParametersDefinitionProperty', parameterDefinitions: [
            [$class: 'StringParameterDefinition', name: 'APP_NAME', defaultValue: 'aloha', description: "Application Name - all resources use this name as a lebel"],
            [$class: 'StringParameterDefinition', name: 'GIT_URL', defaultValue: 'https://github.com/eformat', description: "Git URL (concat with app name for checkout"],
            [$class: 'StringParameterDefinition', name: 'OPENSHIFT_MASTER', defaultValue: 'ose-fb-master.hosts.fabric8.com:8443', description: "host:port of OpenShift master API"],
            [$class: 'StringParameterDefinition', name: 'SONARQUBE', defaultValue: 'sonarqube.sonarqube.svc.cluster.local.', description: "OpenShift Sonarqube Service Name (assumes default port 9000)"],
            [$class: 'StringParameterDefinition', name: 'OPENSHIFT_REGISTRY', defaultValue: 'docker-registry.default.svc.cluster.local.', description: "OpenShift Registry Service Name (assumes default port 5000)"],
            [$class: 'StringParameterDefinition', name: 'CRED_OPENSHIFT_DEV', defaultValue: 'CRED_OPENSHIFT_DEV', description: "ID of Development OSE Jenkins credential"],
            [$class: 'StringParameterDefinition', name: 'CRED_OPENSHIFT_QA', defaultValue: 'CRED_OPENSHIFT_QA', description: "ID of Test OSE Jenkins credential"],
            [$class: 'StringParameterDefinition', name: 'CRED_OPENSHIFT_PROD', defaultValue: 'CRED_OPENSHIFT_PROD', description: "ID of Production OSE Jenkins credential"],
            [$class: 'StringParameterDefinition', name: 'DEV_POD_NUMBER', defaultValue: '1', description: "Number of development pods we desire"],
            [$class: 'StringParameterDefinition', name: 'QA_POD_NUMBER', defaultValue: '1', description: "Number of test pods we desire"],
            [$class: 'StringParameterDefinition', name: 'PROD_POD_NUMBER', defaultValue: '2', description: "Number of production pods we desire"],
            [$class: 'StringParameterDefinition', name: 'SKIP_TESTS', defaultValue: 'true', description: "Skip Test Stages (true || false)"],
            [$class: 'StringParameterDefinition', name: 'PROJECT_PER_DEV_BUILD', defaultValue: 'true', description: "Create A Project Per Dev Build (true || false)"],
            [$class: 'StringParameterDefinition', name: 'PROJECT_PER_TEST_BUILD', defaultValue: 'true', description: "Create A Project Per Test Build (true || false)"],
            [$class: 'StringParameterDefinition', name: 'PROD_PROJECT_NAME', defaultValue: 'redhatmsa', description: "Production Project Name (redhatmsa)"]
        ]]
    ])

    // jenkins environment variables
    echo "Build Number is: ${env.BUILD_NUMBER}"
    echo "Branch name is: ${env.BRANCH_NAME}"
    echo "Git URL is: ${GIT_URL}"

    // build properties (acts as check - these echo's will fail if properties not bound)
    echo "Application Name is: ${APP_NAME}"    
    echo "OpenShift Master is: ${OPENSHIFT_MASTER}"
    echo "OpenShist Registry is: ${OPENSHIFT_REGISTRY}"
    echo "Sonarqube is: ${SONARQUBE}"
    echo "Developer Credential ID is: ${CRED_OPENSHIFT_DEV}"
    echo "Test Credential ID is: ${CRED_OPENSHIFT_QA}"
    echo "Production Credential ID is: ${CRED_OPENSHIFT_PROD}"
    echo "Expected Dev Pod Number is: ${DEV_POD_NUMBER}"
    echo "Expected QA Pod Number is: ${QA_POD_NUMBER}"
    echo "Expected Prod Pod Number is: ${PROD_POD_NUMBER}"
    echo "Skip Tests is: ${SKIP_TESTS}"
    echo "Project per Dev Build is: ${PROJECT_PER_DEV_BUILD}"
    echo "Project per Test Build is: ${PROJECT_PER_TEST_BUILD}"
    echo "Production Project Name is: ${PROD_PROJECT_NAME}"

    stage 'Git checkout'
    echo 'Checking out git repository'
    git url: "${GIT_URL}/${APP_NAME}", branch: "${env.BRANCH_NAME}"

    // tools
    def mvnHome = tool 'M3'
    def javaHome = tool 'jdk8'
    def sonarHome =  tool 'SQ'

    stage 'Build project with Maven'
    echo 'Building project'
    sh "${mvnHome}/bin/mvn clean package"

    def devProject = ''
    if ("${PROJECT_PER_DEV_BUILD}"=='true') {
        devProject = "${APP_NAME}-dev-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
    } else {
        devProject = "${APP_NAME}-dev"
    }

    def testProject = ''
    if ("${PROJECT_PER_TEST_BUILD}"=='true') {
        testProject = "${APP_NAME}-qa-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
    } else {
        testProject = "${APP_NAME}-qa"
    }    

    stage 'Build image and deploy in Dev'
    echo 'Building docker image and deploying to Dev'
    buildProject("${devProject}", "${CRED_OPENSHIFT_DEV}", "${DEV_POD_NUMBER}")

    stage 'Verify deployment in Dev'
    verifyDeployment("${devProject}", "${CRED_OPENSHIFT_DEV}", '1')

    if ("${SKIP_TESTS}"=='false') {
        stage 'Automated tests'
        parallel(
           unitTests:{
                echo 'This stage simulates automated unit tests'
                sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
            }, sonarAnalysis: {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'sonar-dev',
                    usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                    echo 'run sonar tests'
                    sonarIP = getIP("${SONARQUBE}")
                    sh "${sonarHome}/bin/sonar-scanner -Dsonar.projectKey=${APP_NAME} -Dsonar.projectName=${APP_NAME} -Dsonar.host.url=http://${sonarIP}:9000 -Dsonar.login=admin -Dsonar.password=admin -Dsonar.projectVersion=${env.BRANCH_NAME} -Dsonar.sources=src/main"
                    //sh "${mvnHome}/bin/mvn -Dsonar.scm.disabled=True -Dsonar.jdbc.username=$USERNAME -Dsonar.jdbc.password=$PASSWORD sonar:sonar"
                }
            }, seleniumTests: {
                echo 'This stage simulates web ui tests'
                sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
                // sh "${mvnHome}/bin/mvn test"
            }, owaspAnalysis: {
                echo 'This stage checks dependencies for vulnerabilities'
                build job: "${APP_NAME}-dependency-check", wait: true
            }, failFast: true
        )
    }

    stage 'Deploy to QA'
    echo 'Deploying to QA'
    deployProject("${devProject}", "${testProject}", "${CRED_OPENSHIFT_DEV}", "${CRED_OPENSHIFT_QA}", 'promote', "${DEV_POD_NUMBER}")

    stage 'Verify deployment in QA'
    verifyDeployment("${testProject}", "${CRED_OPENSHIFT_QA}", "${QA_POD_NUMBER}")

    stage 'Wait for approval'
    input 'Approve to production?'
        
    stage 'Deploy to production'
    echo 'Deploying to production'
    deployProject("${devProject}", "${PROD_PROJECT_NAME}", "${CRED_OPENSHIFT_DEV}", "${CRED_OPENSHIFT_PROD}", 'prod', "${PROD_POD_NUMBER}")

    stage 'Verify deployment in Production'
    verifyDeployment("${PROD_PROJECT_NAME}", "${CRED_OPENSHIFT_PROD}", "${PROD_POD_NUMBER}")
    
    stage 'Wait for Delete Development & Test Projects'
    input 'Delete Development & Test Projects?' 
    echo 'Delete Development & Test Projects'
    deleteProject("${devProject}", "${CRED_OPENSHIFT_DEV}")
    deleteProject("${testProject}", "${CRED_OPENSHIFT_QA}")    
}

// Delete a Project
def deleteProject(String project, String credentialsId){
    projectSet(project, credentialsId)
    sh "oc delete project ${project} || echo 'Delete project failed'"
}

// Creates a Build and triggers it
def buildProject(String project, String credentialsId, String replicas){
    projectSet(project, credentialsId)
    sh "oc new-build --binary --name=${APP_NAME} -l app=${APP_NAME} || echo 'Build exists'"
    sh "oc start-build ${APP_NAME} --from-dir=. --follow --wait=true"
    appDeploy(project, 'latest', replicas)
}

// Tag the ImageStream from an original project to force a deployment
def deployProject(String origProject, String project, String origCredentialsId, String credentialsId, String tag, String replicas){
    // tag image and give upstream project view and image pull access
    projectSet(origProject, origCredentialsId)
    sh "oc tag ${origProject}/${APP_NAME}:latest ${origProject}/${APP_NAME}:${tag}"
    sh "oc policy add-role-to-user system:image-puller system:serviceaccount:${project}:default -n ${origProject}"

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc policy add-role-to-user view $env.USERNAME"
    }

    // change to upstream project
    projectSet(project, credentialsId)
    // deploy origproject image to upstream project
    appDeploy(origProject, tag, replicas)
}

// Login and set the project
def projectSet(String project, String credentialsId){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc login --insecure-skip-tls-verify=true -u $env.USERNAME -p $env.PASSWORD https://${OPENSHIFT_MASTER}"
    }
    sh "oc new-project ${project} || echo 'Project exists'"
    sh "oc project ${project}"
}

// Deploy the project based on a existing ImageStream
def appDeploy(String project, String tag, String replicas){
    if (fileExists('status')) {
        sh "rm -f status"
    }
    sh "oc new-app --image-stream ${project}/${APP_NAME}:${tag} -l app=${APP_NAME},hystrix.enabled=true,group=msa,project=${APP_NAME},provider=fabric8 || echo \$? > status"
        def ret = 0
    if (fileExists('status')) {
        // app exists - non zero exit code
        sh "echo 'Application already Exists'"
        // patch dc with current project rolling deploy is default strategy
        registryIP = getIP("${OPENSHIFT_REGISTRY}")
        sh "oc set triggers dc/${APP_NAME} --manual"
        def patch1 = $/oc patch dc/"${APP_NAME}" -p $'{\"spec\":{\"triggers\":[{\"type\": \"ConfigChange\"},{\"type\":\"ImageChange\",\"imageChangeParams\":{\"automatic\":true,\"containerNames\":[\"${APP_NAME}\"],\"from\":{\"kind\":\"ImageStreamTag\",\"namespace\":\"${project}\",\"name\":\"${APP_NAME}:${tag}\"}}}]}}$' -p $'{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${APP_NAME}\",\"image\":\"${registryIP}:5000$/${project}$/${APP_NAME}:${tag}\"}]}}}}$'/$
        sh patch1
        sh "oc deploy dc/${APP_NAME} --latest"
        sh "oc set triggers dc/${APP_NAME} --auto"
    } else {
        // new application
        def patch2 = $/oc patch dc/"${APP_NAME}" -p $'{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${APP_NAME}\",\"ports\":[{\"containerPort\":8778,\"name\":\"jolokia\"}]}]}}}}$' -p $'{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${APP_NAME}\",\"readinessProbe\":{\"httpGet\":{\"path\":\"/api/health\",\"port\":8080}}}]}}}}$'/$
        sh patch2
    }
    sh "oc expose service ${APP_NAME} || echo 'Service already exposed'"
    sh "oc scale dc/${APP_NAME} --replicas ${replicas}"
}

// Get Token for Openshift Plugin authToken
def getToken(String credentialsId){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc login --insecure-skip-tls-verify=true -u $env.USERNAME -p $env.PASSWORD https://${OPENSHIFT_MASTER}"
        sh "oc whoami -t > token"
        token = readFile 'token'
        token = token.trim()
        sh 'rm token'
        return token        
    }
}

// Verify Openshift deploy
def verifyDeployment(String project, String credentialsId, String podReplicas){
    projectSet(project, credentialsId)
    def authToken = getToken(credentialsId)    
    openShiftVerifyDeployment(authToken: "${authToken}", namespace: "${project}", depCfg: "${APP_NAME}", replicaCount:"${podReplicas}", verifyReplicaCount: 'true', waitTime: '180000')
}

// Get A Service Cluster IP
def getIP(String lookup){
    sh "getent hosts ${lookup} | cut -f 1 -d \" \" > ipaddress"
    ipaddress = readFile 'ipaddress'
    ipaddress = ipaddress.trim()
    sh 'rm ipaddress'
    return ipaddress
}