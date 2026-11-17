package enxame.worker.provedores;

import java.net.URI;
import java.net.http.*;

// Ajudante dos provedores reais: manda um JSON por HTTP e tira um campo de texto da resposta.
class Http {

    static String post(String url, String json, String chave) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (chave != null) req.header("Authorization", "Bearer " + chave); // so o Groq usa chave
        HttpResponse<String> r = HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception("HTTP " + r.statusCode() + ": " + r.body());
        return r.body();
    }

    // coloca o texto entre aspas, escapando o que quebraria o JSON
    static String texto(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    // pega o valor de "campo":"..." no JSON (sem biblioteca, le ate a aspa que fecha)
    static String campo(String json, String campo) {
        int i = json.indexOf("\"" + campo + "\"");
        i = json.indexOf('"', json.indexOf(':', i) + 1) + 1; // pula ate a aspa que abre o valor
        StringBuilder sb = new StringBuilder();
        for (char c; (c = json.charAt(i)) != '"'; i++) {
            if (c == '\\') { // caractere escapado
                c = json.charAt(++i);
                if (c == 'n') c = '\n';
                else if (c == 't') c = '\t';
                else if (c == 'u') { c = (char) Integer.parseInt(json.substring(i + 1, i + 5), 16); i += 4; }
            }
            sb.append(c);
        }
        return sb.toString().replace("\r", "");
    }
}
