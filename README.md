# 📄 Scanner ID (LPNScanner)

## 📌 Visão Geral
O **Scanner ID (LPNScanner)** é um aplicativo mobile desenvolvido para leitura e controle de etiquetas LPN (License Plate Number), com foco em automação de processos logísticos e rastreabilidade de ativos.

O sistema permite capturar, armazenar e gerar relatórios a partir das leituras realizadas em campo, reduzindo erros manuais e aumentando a produtividade operacional.

---

## 🎯 Objetivo
- Automatizar a leitura de etiquetas LPN  
- Garantir rastreabilidade das operações  
- Gerar relatórios (CSV e PDF)  
- Melhorar eficiência operacional  

---

## ⚙️ Tecnologias Utilizadas
- Android (Java)  
- CameraX + ML Kit (leitura de código de barras)  
- Firebase (armazenamento de dados)  
- iText PDF (geração de relatórios)  
- Exportação CSV (integração com Power BI)  

---

## 🔄 Fluxo do Sistema

```mermaid
flowchart TD
    A[Login do usuário] --> B[Iniciar sessão]
    B --> C[Leitura de LPN via câmera]
    C --> D[Armazenamento no Firebase]
    D --> E[Geração de CSV / PDF]
    E --> F[Análise de dados (Power BI)]
