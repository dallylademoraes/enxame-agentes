package enxame.worker;

import java.io.*;
import java.net.*;
import enxame.comum.Mensagem;
import enxame.worker.provedores.*;

// Nó que recebe perguntas e responde usando um provedor de LLM.
// Uso: java -cp out enxame.worker.Worker <porta> <provedor>
public class Worker {

    public static void main(String[] args) throws IOException {
        ServerSocket servidor = new ServerSocket(Integer.parseInt(args[0]));
        ProvedorLLM provedor = switch (args[1]) { // escolhe a LLM pelo nome passado na linha de comando
            case "ollama" -> new ProvedorOllama();
            case "groq" -> new ProvedorGroq();
            default -> new ProvedorFalso();
        };
        System.out.println("Worker na porta " + args[0] + ", provedor " + provedor.nome());

        while (true) {
            try (Socket conexao = servidor.accept();
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(conexao.getInputStream()));
                 PrintWriter saida = new PrintWriter(conexao.getOutputStream(), true)) {

                Mensagem pergunta = Mensagem.deLinha(entrada.readLine());
                long inicio = System.nanoTime();
                Mensagem resposta;
                try {
                    // pede resposta curta: assim as LLMs dao respostas comparaveis e a votacao funciona
                    String texto = pergunta.conteudo + "\nResponda com uma unica palavra, sem explicacao.";
                    resposta = new Mensagem("RESPOSTA", pergunta.id, provedor.responder(texto));
                } catch (Exception e) {
                    resposta = new Mensagem("ERRO", pergunta.id, e.getMessage());
                }
                System.out.println("[" + pergunta.id + "] " + resposta.tipo + " em " + (System.nanoTime() - inicio) / 1_000_000 + " ms");
                saida.println(resposta.paraLinha());
            } catch (Exception e) {
                System.out.println("Conexao com erro: " + e.getMessage()); // linha mal formada nao derruba o Worker
            }
        }
    }
}
