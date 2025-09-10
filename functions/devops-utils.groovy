// ============================================================================
// FONCTIONS JENKINS INLINE - COPY/PASTE READY
// À copier directement dans tes Jenkinsfiles
// ============================================================================

/**
 * Détecte l'environnement basé sur l'URL Jenkins
 * @return String environnement (DEV, STG, PRD)
 */
def getEnvironment() {
    def jobUrl = env.JOB_URL ?: env.BUILD_URL ?: ""
    echo "🔍 URL analysée: ${jobUrl}"
    
    if (jobUrl.contains("job/DEV/job") || jobUrl.contains("/job/DEV/")) {
        echo "✅ Environnement détecté: DEV"
        return "DEV"
    }
    if (jobUrl.contains("job/STG/job") || jobUrl.contains("/job/STG/")) {
        echo "✅ Environnement détecté: STG"
        return "STG"
    }
    if (jobUrl.contains("job/PRD/job") || jobUrl.contains("/job/PRD/")) {
        echo "✅ Environnement détecté: PRD"
        return "PRD"
    }
    
    echo "⚠️ Environnement non détecté, utilisation de DEV par défaut"
    return "DEV"
}

/**
 * Configuration par environnement
 * @param env Environnement
 * @return Map configuration
 */
def getEnvConfig(String env) {
    def configs = [
        "DEV": [
            instanceType: "t3.micro",
            instanceCount: 1,
            needsApproval: false,
            slackChannel: "#dev-team"
        ],
        "STG": [
            instanceType: "t3.small",
            instanceCount: 2,
            needsApproval: false,
            slackChannel: "#staging-team"
        ],
        "PRD": [
            instanceType: "t3.medium",
            instanceCount: 3,
            needsApproval: true,
            slackChannel: "#prod-alerts"
        ]
    ]
    
    def config = configs[env] ?: configs["DEV"]
    echo "⚙️ Config ${env}: ${config}"
    return config
}

/**
 * Approbation conditionnelle
 * @param env Environnement
 * @param message Message d'approbation
 */
def askApprovalIfNeeded(String env, String message = null) {
    def config = getEnvConfig(env)
    message = message ?: "Continuer le déploiement sur ${env} ?"
    
    if (config.needsApproval) {
        echo "🔒 Approbation requise pour ${env}"
        timeout(time: 30, unit: 'MINUTES') {
            input message: message,
                  ok: "Approuver ${env}",
                  submitterParameter: 'APPROVER'
        }
        echo "✅ Approuvé par ${env.APPROVER ?: 'inconnu'}"
    } else {
        echo "⏭️ Pas d'approbation requise pour ${env}"
    }
}

/**
 * Génère un inventaire Ansible simple
 * @param env Environnement
 */
def generateAnsibleInventory(String env) {
    echo "📋 Génération inventaire Ansible pour ${env}"
    
    def inventoryDir = "ansible/inventories/${env}"
    sh "mkdir -p ${inventoryDir}"
    
    // Récupérer outputs Terraform
    def outputs = [:]
    try {
        dir('terraform') {
            def tfOutput = sh(script: 'terraform output -json', returnStdout: true).trim()
            outputs = readJSON text: tfOutput
        }
    } catch (Exception e) {
        echo "⚠️ Impossible de récupérer les outputs Terraform: ${e.message}"
        return false
    }
    
    // Générer le fichier hosts.ini
    def inventory = "# Inventaire ${env} - ${new Date()}\n\n"
    
    // Section webservers
    inventory += "[webservers]\n"
    if (outputs.web_servers?.value) {
        outputs.web_servers.value.each { server ->
            inventory += "${server.name} ansible_host=${server.public_ip} ansible_user=ubuntu\n"
        }
    }
    
    // Section databases
    inventory += "\n[databases]\n"
    if (outputs.db_servers?.value) {
        outputs.db_servers.value.each { server ->
            inventory += "${server.name} ansible_host=${server.private_ip} ansible_user=ubuntu\n"
        }
    }
    
    // Variables globales
    inventory += "\n[all:vars]\n"
    inventory += "environment=${env}\n"
    if (outputs.vpc_id?.value) {
        inventory += "vpc_id=${outputs.vpc_id.value}\n"
    }
    
    // Écrire le fichier
    writeFile file: "${inventoryDir}/hosts.ini", text: inventory
    echo "✅ Inventaire créé: ${inventoryDir}/hosts.ini"
    
    // Afficher le contenu
    sh "cat ${inventoryDir}/hosts.ini"
    return true
}

/**
 * Déploie avec Terraform
 * @param env Environnement
 */
def deployTerraform(String env) {
    echo "🏗️ Déploiement Terraform pour ${env}"
    
    def config = getEnvConfig(env)
    
    dir('terraform') {
        sh """
            terraform init
            terraform plan -var-file="environments/${env}.tfvars" \
                          -var="instance_type=${config.instanceType}" \
                          -var="instance_count=${config.instanceCount}" \
                          -out=tfplan
            terraform apply -auto-approve tfplan
        """
    }
    echo "✅ Terraform ${env} déployé"
}

/**
 * Déploie l'application avec Ansible
 * @param env Environnement
 * @param version Version de l'app
 */
def deployApplication(String env, String version = "latest") {
    echo "🚀 Déploiement application ${version} sur ${env}"
    
    def inventoryPath = "ansible/inventories/${env}/hosts.ini"
    
    // Vérifier l'inventaire
    if (!fileExists(inventoryPath)) {
        echo "📋 Inventaire manquant, génération..."
        if (!generateAnsibleInventory(env)) {
            error "❌ Impossible de générer l'inventaire"
        }
    }
    
    // Test connectivité
    dir('ansible') {
        sh "ansible all -i ${inventoryPath} -m ping --timeout=10"
    }
    
    // Déploiement
    dir('ansible') {
        sh """
            ansible-playbook -i ${inventoryPath} \
                            -e environment=${env} \
                            -e app_version=${version} \
                            playbooks/deploy.yml
        """
    }
    
    echo "✅ Application ${version} déployée sur ${env}"
}

/**
 * Tests post-déploiement
 * @param env Environnement
 */
def runHealthChecks(String env) {
    echo "🏥 Tests de santé pour ${env}"
    
    try {
        dir('ansible') {
            sh """
                ansible webservers -i inventories/${env}/hosts.ini \
                        -m uri -a "url=http://{{ ansible_host }}/health method=GET"
            """
        }
        echo "✅ Tests de santé OK"
        return true
    } catch (Exception e) {
        echo "❌ Tests de santé échoués: ${e.message}"
        return false
    }
}

/**
 * Notification Slack simple
 * @param env Environnement
 * @param status SUCCESS ou FAILURE
 * @param message Message
 */
def notifySlack(String env, String status, String message) {
    if (!env.SLACK_WEBHOOK) {
        echo "📢 ${status}: ${message}"
        return
    }
    
    def config = getEnvConfig(env)
    def emoji = status == "SUCCESS" ? "✅" : "❌"
    def color = status == "SUCCESS" ? "good" : "danger"
    
    def payload = [
        channel: config.slackChannel,
        text: "${emoji} [${env}] ${message}",
        color: color,
        username: "Jenkins"
    ]
    
    try {
        sh """
            curl -X POST '${env.SLACK_WEBHOOK}' \
                 -H 'Content-Type: application/json' \
                 -d '${groovy.json.JsonOutput.toJson(payload)}'
        """
        echo "📱 Notification Slack envoyée"
    } catch (Exception e) {
        echo "⚠️ Erreur notification Slack: ${e.message}"
    }
}

/**
 * Pipeline complète en une fonction
 * @param version Version de l'app
 */
def runFullDeployment(String version = "latest") {
    def env = getEnvironment()
    
    try {
        echo "🎯 Début déploiement ${version} sur ${env}"
        
        // Approbation si nécessaire
        askApprovalIfNeeded(env)
        
        // Infrastructure
        deployTerraform(env)
        
        // Application
        deployApplication(env, version)
        
        // Tests
        if (!runHealthChecks(env)) {
            error "❌ Tests de santé échoués"
        }
        
        // Notification succès
        notifySlack(env, "SUCCESS", "Déploiement ${version} réussi")
        echo "🎉 Déploiement ${version} sur ${env} terminé avec succès"
        
    } catch (Exception e) {
        notifySlack(env, "FAILURE", "Échec déploiement ${version}: ${e.message}")
        echo "💥 Échec déploiement: ${e.message}"
        throw e
    }
}

/**
 * Utilitaire pour stash/unstash l'inventaire
 */
def stashInventory(String env) {
    stash includes: "ansible/inventories/${env}/**", name: "inventory-${env}-${env.BUILD_NUMBER}"
    echo "📦 Inventaire ${env} sauvegardé"
}

def unstashInventory(String env, String buildNumber = null) {
    buildNumber = buildNumber ?: env.BUILD_NUMBER
    unstash "inventory-${env}-${buildNumber}"
    echo "📦 Inventaire ${env} récupéré"
}

/**
 * Rollback simple
 * @param env Environnement
 * @param previousVersion Version précédente
 */
def rollback(String env, String previousVersion) {
    echo "🔄 Rollback vers ${previousVersion} sur ${env}"
    
    askApprovalIfNeeded(env, "Confirmer le rollback vers ${previousVersion} ?")
    
    deployApplication(env, previousVersion)
    
    if (runHealthChecks(env)) {
        notifySlack(env, "SUCCESS", "Rollback vers ${previousVersion} réussi")
    } else {
        notifySlack(env, "FAILURE", "Rollback vers ${previousVersion} échoué")
        error "❌ Rollback échoué"
    }
}

// ============================================================================
// EXEMPLES D'USAGE DANS UNE PIPELINE
// ============================================================================

/*
// EXEMPLE 1: Pipeline simple
pipeline {
    agent any
    parameters {
        string(name: 'VERSION', defaultValue: 'latest')
    }
    stages {
        stage('Deploy') {
            steps {
                script {
                    runFullDeployment(params.VERSION)
                }
            }
        }
    }
}

// EXEMPLE 2: Pipeline modulaire
pipeline {
    agent any
    stages {
        stage('Detect') {
            steps {
                script {
                    env.TARGET_ENV = getEnvironment()
                }
            }
        }
        stage('Approval') {
            steps {
                script {
                    askApprovalIfNeeded(env.TARGET_ENV)
                }
            }
        }
        stage('Infrastructure') {
            steps {
                script {
                    deployTerraform(env.TARGET_ENV)
                    generateAnsibleInventory(env.TARGET_ENV)
                }
            }
        }
        stage('Application') {
            steps {
                script {
                    deployApplication(env.TARGET_ENV, 'v1.2.3')
                }
            }
        }
        stage('Tests') {
            steps {
                script {
                    runHealthChecks(env.TARGET_ENV)
                }
            }
        }
    }
    post {
        success {
            script {
                notifySlack(env.TARGET_ENV, "SUCCESS", "Déploiement terminé")
            }
        }
        failure {
            script {
                notifySlack(env.TARGET_ENV, "FAILURE", "Déploiement échoué")
            }
        }
    }
}

// EXEMPLE 3: Infrastructure seulement
pipeline {
    agent any
    stages {
        stage('Infrastructure Only') {
            steps {
                script {
                    def env = getEnvironment()
                    askApprovalIfNeeded(env)
                    deployTerraform(env)
                    generateAnsibleInventory(env)
                    stashInventory(env)
                }
            }
        }
    }
}
*/