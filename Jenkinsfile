node {
    stage 'Git checkout'
    echo 'Checking out git repository'
    git url: 'https://github.com/eformat/aloha'    

    echo "OpenShift Master is: ${OPENSHIFT_MASTER}"

    stage 'Build project with Maven'
    echo 'Building project'
    def mvnHome = tool 'M3'
    def javaHome = tool 'jdk8'
    def sonarHome = tool 'SQ'
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
                sh '${sonarHome}/bin/sonar-scanner'
                //sh 'mvn -Dsonar.scm.disabled=True -Dsonar.jdbc.username=$USERNAME -Dsonar.jdbc.password=$PASSWORD sonar:sonar'
            }
        }, seleniumTests: {
            echo 'This stage simulates web ui tests'
            sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"
        }, failFast: true
    )
    checkpoint 'Quality Analysis Complete'

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
