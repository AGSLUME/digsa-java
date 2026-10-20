package co.com.agsamericas.digsa.plataforma;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import co.com.agsamericas.digsa.reglas.Bitacora;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Correo por SMTP (el corporativo o el que defina TI). Un rechazo parcial deja quién no lo recibió. */
public class CorreoSmtp implements Correo {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoSmtp.class);

    private final JavaMailSender mailSender;
    private final String remitente;
    private final String nombreRemitente;

    public CorreoSmtp(JavaMailSender mailSender, String remitente, String nombreRemitente) {
        this.mailSender = mailSender;
        this.remitente = remitente;
        this.nombreRemitente = nombreRemitente;
    }

    @Override
    public Resultado enviar(Mensaje m) {
        List<String> todos = new ArrayList<>(m.para());
        todos.addAll(m.copia());
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(mime, !m.adjuntos().isEmpty(), "UTF-8");
            h.setFrom(new InternetAddress(remitente, nombreRemitente, "UTF-8"));
            h.setTo(m.para().toArray(String[]::new));
            if (!m.copia().isEmpty()) {
                h.setCc(m.copia().toArray(String[]::new));
            }
            h.setSubject(m.asunto());
            h.setText(m.html(), true);
            for (Adjunto a : m.adjuntos()) {
                h.addAttachment(a.nombre(), new ByteArrayResource(a.contenido()), a.tipo());
            }
            mailSender.send(mime);
            return Resultado.exito(todos);
        } catch (MailSendException e) {
            // Con mail.smtp.sendpartial=true el servidor entrega a los válidos; solo cuentan como entregados los que de verdad aceptó
            List<String> entregados = direcciones(e, SendFailedException::getValidSentAddresses);
            List<String> rechazados = todos.stream().filter(c -> !entregados.contains(c.toLowerCase())).toList();
            if (!entregados.isEmpty()) {
                LOG.warn("Correo «{}» entregado en parte: {} de {} destinatarios", m.asunto(), entregados.size(), todos.size());
                return new Resultado(true, entregados, rechazados, "Algunos destinatarios rechazaron el correo.");
            }
            LOG.warn("Correo «{}» no enviado: {}", m.asunto(), Bitacora.sinCorreos(e.getMessage()));
            return Resultado.falla(todos, "El servidor de correo rechazó el envío.");
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            LOG.warn("Correo «{}» no enviado: {}", m.asunto(), Bitacora.sinCorreos(e.getMessage()));
            return Resultado.falla(todos, "No se pudo conectar con el servidor de correo.");
        }
    }

    @Override
    public boolean real() {
        return true;
    }

    private static List<String> direcciones(MailSendException e, java.util.function.Function<SendFailedException, Address[]> de) {
        List<String> r = new ArrayList<>();
        for (Exception causa : e.getFailedMessages().values()) {
            if (causa instanceof SendFailedException s && de.apply(s) != null) {
                Arrays.stream(de.apply(s)).map(a -> a instanceof InternetAddress ia ? ia.getAddress() : a.toString())
                        .map(String::toLowerCase).forEach(r::add);
            }
        }
        return r;
    }
}
