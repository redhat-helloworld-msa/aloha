node {
    stage 'Git checkout'
    echo 'Checking out git repository'
    git url: 'https://github.com/eformat/aloha'    

    // environment variables and tools
    echo "OpenShift Master is: ${OPENSHIFT_MASTER}"
    echo "Sonarqube is: ${SONARQUBE}"

    echo "AuthToken is:"
    getToken('openshift-dev')

    def mvnHome = tool 'M3'
    def javaHome = tool 'jdk8'
    def sonarHome =  tool 'SQ'

    stage 'Build project with Maven'
    echo 'Building project'
    sh "${mvnHome}/bin/mvn clean package"

    stage 'Build image and deploy in Dev'
    echo 'Building docker image and deploying to Dev'
    buildAloha('helloworld-msa-dev', 'openshift-dev')

    stage 'Automated tests'
    parallel(
        unitTests:{
            echo 'This stage simulates automated unit tests'
            sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
        }, sonarAnalysis: {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'sonar-dev',
                usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                echo 'run sonar tests'
                sh "${sonarHome}/bin/sonar-scanner -Dsonar.projectKey=aloha -Dsonar.projectName=aloha -Dsonar.host.url=http://${SONARQUBE} -Dsonar.login=admin -Dsonar.password=admin -Dsonar.projectVersion=1.0.0-SNAPSHOT -Dsonar.sources=src/main"
                //sh 'mvn -Dsonar.scm.disabled=True -Dsonar.jdbc.username=$USERNAME -Dsonar.jdbc.password=$PASSWORD sonar:sonar'
            }
        }, seleniumTests: {
            echo 'This stage simulates web ui tests'
            sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
        }, owaspAnalysis: {
            echo 'This stage checks dependencies for vulnerabilities'
            build job: 'aloha-dependency-check', wait: true
        }, failFast: true
    )

    stage 'Deploy to QA'
    echo 'Deploying to QA'
    deployAloha('helloworld-msa-dev', 'helloworld-msa-qa', 'openshift-dev', 'openshift-qa', 'promote')

    stage 'Wait for approval'
    input 'Aprove to production?'

    stage 'Deploy to production'
    echo 'Deploying to production'
    deployAloha('helloworld-msa-dev', 'redhatmsa', 'openshift-dev', 'openshift-prod', 'prod')
}

// Creates a Build and triggers it
def buildAloha(String project, String credentialsId){
    projectSet(project, credentialsId)
    sh "oc new-build --binary --name=aloha -l app=aloha || echo 'Build exists'"
    sh "oc start-build aloha --from-dir=. --follow --wait=true"
    appDeploy(project, 'latest')
}

// Tag the ImageStream from an original project to force a deployment
def deployAloha(String origProject, String project, String origCredentialsId, String credentialsId, String tag){
    // tag image and give upstream project view and image pull access
    projectSet(origProject, origCredentialsId)
    sh "oc tag ${origProject}/aloha:latest ${origProject}/aloha:${tag}"
    sh "oc policy add-role-to-user system:image-puller system:serviceaccount:${project}:default -n ${origProject}"

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc policy add-role-to-user view $env.USERNAME"
    }

    // change to upstream project
    projectSet(project, credentialsId)
    // deploy origproject image to upstream project
    appDeploy(origProject, tag)
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
def appDeploy(String project, String tag){
    sh "oc new-app --image-stream ${project}/aloha:${tag} -l app=aloha,hystrix.enabled=true,group=msa,project=aloha,provider=fabric8 || echo 'Aplication already Exists'"
    sh "oc expose service aloha || echo 'Service already exposed'"
    sh 'oc patch dc/aloha -p \'{"spec":{"template":{"spec":{"containers":[{"name":"aloha","ports":[{"containerPort": 8778,"name":"jolokia"}]}]}}}}\''
    sh 'oc patch dc/aloha -p \'{"spec":{"template":{"spec":{"containers":[{"name":"aloha","readinessProbe":{"httpGet":{"path":"/api/health","port":8080}}}]}}}}\''
}

// Get Token for Openshift Plugin authToken
def getToken(String credentialsId){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "curl -v -XGET \
            --no-keepalive \
            -u "${credentialsId}" \
            -H "X-Csrf-Token: 1" \
            "https://${OPENSHIFT_MASTER}:8443/oauth/authorize?response_type=token&client_id=openshift-challenging-client" \
            2>&1 | \
            grep 'Location: ' | \
            sed -E 's/.*access_token=([^&]+)&.*/\\1/' >token"
        token = readFile 'token'
        token = token.trim()
        sh 'rm token'
        return token
     }
}
