package enxame.coordenador;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*; // ExecutorService e Future (as threads)
import enxame.comum.Mensagem;

// Intermediário: recebe a pergunta do Cliente, repassa a um Worker e devolve a resposta.
// Uso: java -cp out enxame.coordenador.Coordenador <porta> <arquivo-workers>
public class Coordenador {

    // "limpa" a resposta pra comparar: " Paris." e "paris" viram "paris"
    static String normalizar(String texto) {
        return texto.trim().toLowerCase().replaceAll("[.!]+$", "");
    }

    public static void main(String[] args) throws IOException {
        // le o worker.txt e salva um contato pra cada worker
        List<StubWorker> workers = new ArrayList<>(); // agenda vazia
        for (String linha : Files.readAllLines(Path.of(args[1]))) {
            if (linha.isBlank() || linha.startsWith("#")) continue; // pula linha vazia e comentario
            String[] p = linha.split("[: ]"); // p[0] é o host, p[1] é a porta, p[2] é o provedor
            workers.add(new StubWorker(p[0], Integer.parseInt(p[1]), p[2])); // salva o contato
            System.out.println("Worker: " + p[2] + " na porta " + p[1]);
        }

        // abre a porta pro cliente
        ServerSocket servidor = new ServerSocket(Integer.parseInt(args[0]));
        System.out.println("Coordenador na porta " + args[0]);

        // cria os ajudantes (threads), um pra cada worker. cria so uma vez e reaproveita
        ExecutorService ajudantes = Executors.newFixedThreadPool(workers.size());

        while (true) {
            try (Socket conexao = servidor.accept(); // espera o cliente
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(conexao.getInputStream()));
                 PrintWriter saida = new PrintWriter(conexao.getOutputStream(), true)) {

                Mensagem pergunta = Mensagem.deLinha(entrada.readLine()); // recebe do cliente

                long inicio = System.nanoTime(); // marca o inicio pra medir o tempo

                // 1. espalha: cada ajudante leva a pergunta pra um worker, todos ao mesmo tempo
                //    o submit NAO espera a resposta, so dispara e devolve um "recibo" (Future)
                List<Future<Mensagem>> recibos = new ArrayList<>();
                for (StubWorker w : workers) {
                    recibos.add(ajudantes.submit(() -> w.perguntar(pergunta))); // () -> ... e a tarefa do ajudante
                }

                // 2. junta: pega a resposta de cada recibo. o get() espera se ainda nao chegou
                String respostas = ""; // comeca vazio (a bandeja)
                Map<String, Integer> votos = new HashMap<>(); // placar: resposta -> quantos workers deram ela
                for (int i = 0; i < workers.size(); i++) {
                    try { // se um worker falhar, so ele e pulado
                        Mensagem r = recibos.get(i).get(); // resposta desse worker
                        if (r.tipo.equals("ERRO")) throw new Exception(r.conteudo); // worker respondeu ERRO = falhou tambem
                        respostas += "[" + workers.get(i).nome() + "] " + r.conteudo + "\n"; // deu certo: coloca na bandeja
                        votos.merge(normalizar(r.conteudo), 1, Integer::sum); // conta o voto: soma 1 pra essa resposta
                    } catch (Exception e) {
                        // caiu, nao atendeu, demorou mais de 5 s ou respondeu ERRO: anota e segue pro proximo
                        System.out.println("Worker " + workers.get(i).nome() + " falhou: " + e.getMessage());
                        respostas += "[" + workers.get(i).nome() + "] falhou: " + e.getMessage() + "\n"; // avisa o cliente tambem (nao conta como voto)
                    }
                }
                // antes, um de cada vez (sequencial), dava ~3970 ms (a soma dos 3)
                System.out.println("Paralelo: " + (System.nanoTime() - inicio) / 1_000_000 + " ms"); // mostra quanto demorou
                System.out.println("Votos: " + votos); // ex.: {paris=2, lyon=1}

                // acha a resposta mais votada
                String vencedor = "";
                int maior = 0;
                for (Map.Entry<String, Integer> v : votos.entrySet()) {
                    if (v.getValue() > maior) { maior = v.getValue(); vencedor = v.getKey(); }
                }

                // quorum = maioria do total de workers (3 -> 2). conta os configurados, nao so os que responderam
                int quorum = workers.size() / 2 + 1;
                if (maior >= quorum) { // maioria concordou: devolve so a vencedora com o placar
                    saida.println(new Mensagem("RESPOSTA", pergunta.id, vencedor + " (" + maior + " de " + workers.size() + " votos)\n" + respostas).paraLinha()); // + o voto de cada um
                } else { // ninguem chegou na maioria (ou quase todos cairam): avisa o cliente, ele nunca fica travado
                    saida.println(new Mensagem("ERRO", pergunta.id, "sem quorum " + votos + "\n" + respostas).paraLinha());
                }
            } catch (Exception e) {
                System.out.println("Erro: " + e.getMessage()); // mostra o erro e continua funcionando
            }
        }
    }
}
