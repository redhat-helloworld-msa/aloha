node {
    stage 'Git checkout'
    echo 'Checking out git repository'
    git url: 'https://github.com/eformat/aloha'

    stage 'Build project with Maven'
    echo 'Building project'
    def mvnHome = tool 'M3'
    def javaHome = tool 'jdk8'
    sh "${mvnHome}/bin/mvn package"

    stage 'Build image and deploy in Dev'
    echo 'Building docker image and deploying to Dev'
    buildAloha('helloworld-msa-dev', 'openshift-dev')

    stage 'Automated tests'
    echo 'This stage simulates automated tests'
    sh "${mvnHome}/bin/mvn -B -Dmaven.test.failure.ignore verify"

    stage 'Deploy to QA'
    echo 'Deploying to QA'
    deployAloha('helloworld-msa-dev', 'helloworld-msa-qa', 'openshift-dev', 'openshift-qa')

    stage 'Wait for approval'
    input 'Aprove to production?'

    stage 'Deploy to production'
    echo 'Deploying to production'
    deployAloha('helloworld-msa-dev', 'redhatmsa', 'openshift-dev', 'openshift-prod')
}

// Creates a Build and triggers it
def buildAloha(String project, String credentialsId){
    projectSet(project, credentialsId)
    sh "oc new-build --binary --name=aloha -l app=aloha || echo 'Build exists'"
    sh "oc start-build aloha --from-dir=. --follow"
    appDeploy(project, 'latest')
}

// Tag the ImageStream from an original project to force a deployment
def deployAloha(String origProject, String project, String origCredentialsId, String credentialsId){
    // change to upstream project to get user
    projectSet(project, credentialsId)

    // tag image and give upstream project view and image pull access
    projectSet(origProject, origCredentialsId)
    sh "oc tag ${origProject}/aloha:latest ${origProject}/aloha:promote"
    sh "oc policy add-role-to-user system:image-puller system:serviceaccount:${project} -n ${origProject}"

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc policy add-role-to-user view $env.USERNAME"
    }

    // change to upstream project
    projectSet(project, credentialsId)
    // deploy to upstream project
    appDeploy(origProject, 'promote')
}

// Login and set the project
def projectSet(String project, String credentialsId){
    //Use a credential called openshift-dev
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "${credentialsId}", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh "oc login --insecure-skip-tls-verify=true -u $env.USERNAME -p $env.PASSWORD https://ose-master.hosts.example.com:8443"
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

