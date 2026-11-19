package enxame.coordenador;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import enxame.comum.Mensagem;

// Intermediário: recebe a pergunta do Cliente, manda para todos os Workers e devolve a resposta mais votada
public class Coordenador {

    // padroniza as RESPOSTAS para a votação: "Paris." e "Paris" viram "paris"
    static String normalizar(String texto) {
        return texto.trim().toLowerCase().replaceAll("[.!]+$", "");
    }

    public static void main(String[] args) throws IOException {
        // PARTE 1: PREPARAR (roda uma vez só)

        // monta a agenda de contatos: um StubWorker para cada linha do workers.txt
        List<StubWorker> workers = new ArrayList<>();
        for (String linha : Files.readAllLines(Path.of(args[1]))) {
            if (linha.isBlank() || linha.startsWith("#")) continue; // pula linha vazia e comentário
            String[] p = linha.split("[: ]"); // p[0] = host, p[1] = porta, p[2] = provedor
            workers.add(new StubWorker(p[0], Integer.parseInt(p[1]), p[2]));
        }

        ServerSocket servidor = new ServerSocket(Integer.parseInt(args[0])); // abre a porta para o cliente
        ExecutorService ajudantes = Executors.newFixedThreadPool(workers.size()); // uma thread por worker
        System.out.println("Coordenador na porta " + args[0]);

        while (true) {
            try (Socket conexao = servidor.accept(); // espera o cliente
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(conexao.getInputStream()));
                 PrintWriter saida = new PrintWriter(conexao.getOutputStream(), true)) {

                Mensagem pergunta = Mensagem.deLinha(entrada.readLine()); // recebe a pergunta do cliente

                // PARTE 2: PERGUNTAR

                // espalha: cada ajudante liga para um worker, todos ao mesmo tempo. submit devolve um recibo (Future)
                List<Future<Mensagem>> recibos = new ArrayList<>();
                for (StubWorker w : workers) {
                    recibos.add(ajudantes.submit(() -> w.perguntar(pergunta)));
                }

                // junta: get() busca a resposta de cada recibo (espera se ainda não chegou)
                String respostas = "";                         // resposta de cada worker, para mostrar ao cliente
                Map<String, Integer> votos = new HashMap<>();  // placar: resposta -> quantos votos
                for (int i = 0; i < workers.size(); i++) {
                    String nome = workers.get(i).nome();
                    try {
                        Mensagem r = recibos.get(i).get();
                        if (r.tipo.equals("ERRO")) throw new Exception(r.conteudo);
                        respostas += "[" + nome + "] " + r.conteudo + "\n";
                        votos.merge(normalizar(r.conteudo), 1, Integer::sum); // +1 voto para essa resposta
                    } catch (Exception e) { // worker caiu, demorou ou deu ERRO: anota e segue (não vota)
                        respostas += "[" + nome + "] falhou: " + e.getMessage() + "\n";
                    }
                }

                // PARTE 3: VOTAR

                // acha a resposta mais votada
                String vencedor = "";
                int maior = 0;
                for (Map.Entry<String, Integer> v : votos.entrySet()) {
                    if (v.getValue() > maior) { maior = v.getValue(); vencedor = v.getKey(); }
                }

                int quorum = workers.size() / 2 + 1; // maioria: 3 workers -> 2 votos
                if (maior >= quorum) {
                    saida.println(new Mensagem("RESPOSTA", pergunta.id, vencedor + " (" + maior + " de " + workers.size() + " votos)\n" + respostas).paraLinha());
                } else {
                    saida.println(new Mensagem("ERRO", pergunta.id, "sem quorum " + votos + "\n" + respostas).paraLinha());
                }
            } catch (Exception e) {
                System.out.println("Erro: " + e.getMessage()); // mostra o erro e continua atendendo
            }
        }
    }
}
