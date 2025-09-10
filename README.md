# 🚀 DevOps Pipeline Toolkit

Fonctions Jenkins **copy/paste ready** pour automatiser vos déploiements DEV/STG/PRD.

## ✨ Fonctionnalités

- 🤖 **Auto-détection d'environnement** via URL Jenkins
- ⚙️ **Configuration automatique** par environnement  
- 🔒 **Approbation automatique** en production
- 📋 **Génération d'inventaire Ansible** depuis Terraform
- 🏥 **Tests automatiques** post-déploiement
- 📱 **Notifications Slack** intégrées
- 🔄 **Rollback** en un clic

## 🏗️ Structure Jenkins requise

Créer cette structure de jobs dans Jenkins :

```
Jenkins/
├── DEV/
│   └── MonApp-Pipeline
├── STG/  
│   └── MonApp-Pipeline
└── PRD/
    └── MonApp-Pipeline
```

**L'auto-détection fonctionnera automatiquement !**

## ⚡ Quick Start

### Option 1 : Pipeline complète (recommandé)

1. Copier le fichier `Jenkinsfile.template`
2. L'adapter à votre projet
3. Configurer les variables d'environnement Jenkins :
   - `SLACK_WEBHOOK` (optionnel)
   - Credentials AWS
4. C'est tout ! 🎉

### Option 2 : Fonctions personnalisées

1. Copier les fonctions depuis `functions/devops-utils.groovy`
2. Les coller dans votre Jenkinsfile
3. Utiliser selon vos besoins

## 📖 Exemples d'usage

### Pipeline ultra-simple
```groovy
pipeline {
    agent any
    stages {
        stage('Deploy') {
            steps {
                script {
                    def env = getEnvironment()          // Auto-détection
                    askApprovalIfNeeded(env)           // Approbation si PRD
                    deployTerraform(env)               // Infrastructure
                    generateAnsibleInventory(env)       // Inventaire
                    deployApplication(env, 'v1.2.3')   // Application
                    runHealthChecks(env)               // Tests
                }
            }
        }
    }
}
// + fonctions copiées en bas du fichier
```

### Pipeline avec paramètres
```groovy
pipeline {
    agent any
    parameters {
        string(name: 'VERSION', defaultValue: 'latest')
        choice(name: 'ACTION', choices: ['deploy', 'rollback'])
    }
    stages {
        stage('Action') {
            steps {
                script {
                    def env = getEnvironment()
                    
                    if (params.ACTION == 'deploy') {
                        // Déploiement normal
                        askApprovalIfNeeded(env)
                        deployTerraform(env)
                        deployApplication(env, params.VERSION)
                    } else {
                        // Rollback
                        rollback(env, params.VERSION)
                    }
                }
            }
        }
    }
}
```

## 🔧 Configuration

### Variables d'environnement Jenkins

- `SLACK_WEBHOOK` : URL webhook Slack (optionnel)
- Credentials AWS configurés
- Credentials SSH pour Ansible

### Structure de fichiers

```
votre-projet/
├── Jenkinsfile                 # Pipeline principale
├── terraform/
│   ├── environments/
│   │   ├── DEV.tfvars         # Config DEV
│   │   ├── STG.tfvars         # Config STG
│   │   └── PRD.tfvars         # Config PRD
│   └── outputs.tf             # Outputs pour Ansible
└── ansible/
    ├── playbooks/
    │   └── deploy.yml         # Playbook de déploiement
    └── inventories/           # Généré automatiquement
```

### Outputs Terraform requis

Votre `outputs.tf` doit contenir :

```hcl
output "web_servers" {
  value = [
    for instance in aws_instance.web : {
      name      = instance.tags.Name
      public_ip = instance.public_ip
    }
  ]
}

output "db_servers" {
  value = [
    for instance in aws_instance.db : {
      name       = instance.tags.Name
      private_ip = instance.private_ip
    }
  ]
}
```

## 🎯 Fonctions disponibles

| Fonction | Description | Usage |
|----------|-------------|-------|
| `getEnvironment()` | Auto-détection DEV/STG/PRD | `def env = getEnvironment()` |
| `getEnvConfig(env)` | Config par environnement | `def config = getEnvConfig("PRD")` |
| `askApprovalIfNeeded(env)` | Approbation conditionnelle | `askApprovalIfNeeded("PRD")` |
| `deployTerraform(env)` | Déploiement infrastructure | `deployTerraform("STG")` |
| `generateAnsibleInventory(env)` | Création inventaire | `generateAnsibleInventory("DEV")` |
| `deployApplication(env, version)` | Déploiement app | `deployApplication("PRD", "v1.0")` |
| `runHealthChecks(env)` | Tests de santé | `if (runHealthChecks("PRD")) {...}` |
| `notifySlack(env, status, msg)` | Notification | `notifySlack("PRD", "SUCCESS", "OK")` |
| `rollback(env, version)` | Rollback | `rollback("PRD", "v0.9")` |

## 🔍 Comment ça marche ?

1. **Auto-détection** : Analyse l'URL du job Jenkins
   - `*/job/DEV/job/*` → Environnement DEV
   - `*/job/STG/job/*` → Environnement STG  
   - `*/job/PRD/job/*` → Environnement PRD

2. **Configuration automatique** : Charge la config selon l'environnement
   - DEV : `t3.micro`, pas d'approbation
   - STG : `t3.small`, monitoring activé
   - PRD : `t3.medium`, approbation requise

3. **Workflow intelligent** :
   - Approbation → Terraform → Inventaire → Ansible → Tests → Notification

## 🆘 Support

### Problèmes courants

**Environnement non détecté :**
- Vérifier la structure des jobs Jenkins
- L'URL doit contenir `/job/DEV/job/` ou similaire

**Inventaire vide :**
- Vérifier les outputs Terraform
- S'assurer que `terraform output -json` fonctionne

**Tests de santé échoués :**
- Vérifier que l'endpoint `/health` existe
- Contrôler la connectivité réseau

### Debug

Ajouter dans votre pipeline :
```groovy
script {
    echo "🔍 Debug info:"
    echo "- JOB_URL: ${env.JOB_URL}"
    echo "- BUILD_URL: ${env.BUILD_URL}"
    echo "- Detected ENV: ${getEnvironment()}"
}
```

## 📞 Contact

Pour toute question ou amélioration :
- 📧 Email : votre.email@company.com
- 💬 Slack : #devops-support
- 📋 Issues : GitHub Issues

---

> 💡 **Astuce** : Commencez par tester en DEV, puis STG, puis PRD !
