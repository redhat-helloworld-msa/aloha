node {
    // environment variables and tools
    echo "OpenShift Master is: ${OPENSHIFT_MASTER}"
    echo "Sonarqube is: ${SONARQUBE}"
    echo "Project Name is: ${PROJECT_NAME}"
    echo "Expected Dev Pod Number is: ${DEV_POD_NUMBER}"
    echo "Expected QA Pod Number is: ${QA_POD_NUMBER}"
    echo "Expected Prod Pod Number is: ${PROD_POD_NUMBER}"

    stage 'Git checkout'
    echo 'Checking out git repository'
    git url: "https://github.com/eformat/${PROJECT_NAME}"

    def mvnHome = tool 'M3'
    def javaHome = tool 'jdk8'
    def sonarHome =  tool 'SQ'

    stage 'Build project with Maven'
    echo 'Building project'
    sh "${mvnHome}/bin/mvn clean package"

    stage 'Build image and deploy in Dev'
    echo 'Building docker image and deploying to Dev'
    buildProject('helloworld-msa-dev', 'openshift-dev', "${DEV_POD_NUMBER}".toInteger())

    stage 'Verify deployment in Dev'
    verifyDeployment('helloworld-msa-dev', 'openshift-dev', '1')

    stage 'Automated tests'
    parallel(
        unitTests:{
            echo 'This stage simulates automated unit tests'
            sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
        }, sonarAnalysis: {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'sonar-dev',
                usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                echo 'run sonar tests'
                sh "${sonarHome}/bin/sonar-scanner -Dsonar.projectKey=${PROJECT_NAME} -Dsonar.projectName=${PROJECT_NAME} -Dsonar.host.url=http://${SONARQUBE} -Dsonar.login=admin -Dsonar.password=admin -Dsonar.projectVersion=1.0.0-SNAPSHOT -Dsonar.sources=src/main"
                //sh "${mvnHome}/bin/mvn -Dsonar.scm.disabled=True -Dsonar.jdbc.username=$USERNAME -Dsonar.jdbc.password=$PASSWORD sonar:sonar"
            }
        }, seleniumTests: {
            echo 'This stage simulates web ui tests'
            sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
            // sh "${mvnHome}/bin/mvn test"
        }, owaspAnalysis: {
            echo 'This stage checks dependencies for vulnerabilities'
            build job: "${PROJECT_NAME}-dependency-check", wait: true
        }, failFast: true
    )

    stage 'Deploy to QA'
    echo 'Deploying to QA'
    deployProject('helloworld-msa-dev', 'helloworld-msa-qa', 'openshift-dev', 'openshift-qa', 'promote', "${DEV_POD_NUMBER}")

    stage 'Verify deployment in QA'
    verifyDeployment('helloworld-msa-qa', 'openshift-qa', "${QA_POD_NUMBER}")

    stage 'Wait for approval'
    input 'Approve to production?'

    stage 'Deploy to production'
    echo 'Deploying to production'
    deployProject('helloworld-msa-dev', 'redhatmsa', 'openshift-dev', 'openshift-prod', 'prod', "${PROD_POD_NUMBER}")

    stage 'Verify deployment in Production'
    verifyDeployment('redhatmsa', 'openshift-prod', ${PROD_POD_NUMBER})
}

// Creates a Build and triggers it
def buildProject(String project, String credentialsId, String replicas){
    projectSet(project, credentialsId)
    sh "oc new-build --binary --name=${PROJECT_NAME} -l app=${PROJECT_NAME} || echo 'Build exists'"
    sh "oc start-build ${PROJECT_NAME} --from-dir=. --follow --wait=true"
    appDeploy(project, 'latest', replicas)
}

// Tag the ImageStream from an original project to force a deployment
def deployProject(String origProject, String project, String origCredentialsId, String credentialsId, String tag, String replicas){
    // tag image and give upstream project view and image pull access
    projectSet(origProject, origCredentialsId)
    sh "oc tag ${origProject}/${PROJECT_NAME}:latest ${origProject}/${PROJECT_NAME}:${tag}"
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
    //Use a credential called openshift-dev
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc login --insecure-skip-tls-verify=true -u $env.USERNAME -p $env.PASSWORD https://${OPENSHIFT_MASTER}"
    }
    sh "oc new-project ${project} || echo 'Project exists'"
    sh "oc project ${project}"
}

// Deploy the project based on a existing ImageStream
def appDeploy(String project, String tag, String replicas){
    sh "oc new-app --image-stream ${project}/${PROJECT_NAME}:${tag} -l app=${PROJECT_NAME},hystrix.enabled=true,group=msa,project=${PROJECT_NAME},provider=fabric8 || echo 'Aplication already Exists'"
    sh "oc expose service ${PROJECT_NAME} || echo 'Service already exposed'"
    sh 'oc patch dc/${PROJECT_NAME} -p \'{"spec":{"template":{"spec":{"containers":[{"name":"${PROJECT_NAME}","ports":[{"containerPort": 8778,"name":"jolokia"}]}]}}}}\''    
    sh 'oc patch dc/${PROJECT_NAME} -p \'{"spec":{"template":{"spec":{"containers":[{"name":"${PROJECT_NAME}","readinessProbe":{"httpGet":{"path":"/api/health","port":8080}}}]}}}}\''
    sh "oc scale dc/${PROJECT_NAME} --replicas ${replicas}"
}

// Get Token for Openshift Plugin authToken
def getToken(String credentialsId){
    //Use a credential called openshift-dev
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
    openShiftVerifyDeployment(authToken: "${authToken}", namespace: "${project}", depCfg: '${PROJECT_NAME}', replicaCount:"${podReplicas}", verifyReplicaCount: 'true', waitTime: '180000')
}
