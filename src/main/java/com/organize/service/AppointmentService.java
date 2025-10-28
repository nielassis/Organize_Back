package com.organize.service;

import com.organize.dto.AppointmentRequestDTO;
import com.organize.model.*;
import com.organize.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final OfferedServiceRepository offeredServiceRepository;
    private final UserRepository userRepository;
    private final EstablishmentRepository establishmentRepository;
    private final EmployeeRepository employeeRepository;
    private final WebhookRepository webhookRepository;
    private final WebhookService webhookService;
    private final TransactionsRepository transactionsRepository;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              OfferedServiceRepository offeredServiceRepository,
                              UserRepository userRepository,
                              EstablishmentRepository establishmentRepository,
                              EmployeeRepository employeeRepository,
                              WebhookRepository webhookRepository,
                              WebhookService webhookService, TransactionsRepository transactionsRepository) {
        this.appointmentRepository = appointmentRepository;
        this.offeredServiceRepository = offeredServiceRepository;
        this.userRepository = userRepository;
        this.establishmentRepository = establishmentRepository;
        this.employeeRepository = employeeRepository;
        this.webhookRepository = webhookRepository;
        this.webhookService = webhookService;
        this.transactionsRepository = transactionsRepository;
    }

    public List<Appointment> getAppointmentsByUserAndDateRange(UUID userId, LocalDateTime start, LocalDateTime end) {
        return appointmentRepository.findAppointmentsByClientAndDateRange(userId, start, end);
    }

    public List<Appointment> getAppointmentsByEstablishmentAndDate(UUID adminId, LocalDateTime start, LocalDateTime end) {
        Establishment establishment = establishmentRepository.findByOwnerId(adminId)
                .orElseThrow(() -> new RuntimeException("Estabelecimento não encontrado para admin: " + adminId));

        return appointmentRepository.findAppointmentsByEstablishmentAndDateRange(establishment.getId(), start, end);
    }

    public Appointment createAppointment(AppointmentRequestDTO request, User loggedUser) {

        User client = userRepository.findById(loggedUser.getId())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        OfferedService service = offeredServiceRepository.findById(request.serviceId())
                .orElseThrow(() -> new RuntimeException("Serviço não encontrado"));

        Establishment establishment = establishmentRepository.findById(request.establishmentId())
                .orElseThrow(() -> new RuntimeException("Estabelecimento não encontrado"));

        Employee employee = employeeRepository.findById(request.employeeId())
                .orElseThrow(() -> new RuntimeException("Funcionário não encontrado"));

        boolean hasConflict = appointmentRepository.isEmployeeUnavailable(
                employee.getId(), request.startTime(), request.endTime()
        );

        if (hasConflict) {
            throw new RuntimeException("Funcionário já possui agendamento nesse horário");
        }

        Appointment appointment = new Appointment();
        appointment.setClient(client);
        appointment.setService(service);
        appointment.setEstablishment(establishment);
        appointment.setEmployee(employee);
        appointment.setStartTime(request.startTime());
        appointment.setEndTime(request.endTime());
        appointment.setStatus(request.status() != null ? request.status() : AppointmentStatus.PENDING);
        appointment.setClientNotes(request.clientNotes());

        Appointment savedAppointment = appointmentRepository.save(appointment);

        List<Webhook> adminWebhooks = webhookRepository.findByEventType("APPOINTMENT_CREATED");
        webhookService.triggerWebhooks(adminWebhooks, Map.of(
                "event", "APPOINTMENT_CREATED",
                "appointmentId", savedAppointment.getId(),
                "clientName", savedAppointment.getClient().getName(),
                "startTime", savedAppointment.getStartTime()
        ));

        return savedAppointment;
    }

    public Appointment updateStatus(UUID id, String status) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agendamento não encontrado"));

        AppointmentStatus newStatus;
        try {
            newStatus = AppointmentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Status inválido: " + status);
        }

        appointment.setStatus(newStatus);
        Appointment savedAppointment = appointmentRepository.save(appointment);

        if (newStatus == AppointmentStatus.COMPLETED && savedAppointment.getService() != null) {
            
            Transaction transaction = new Transaction();
            
            transaction.setAppointmentId(savedAppointment.getId());
            transaction.setEstablishmentId(savedAppointment.getEstablishment().getId());
            
            transaction.setDescription(savedAppointment.getService().getName()); 
            
            transaction.setAmountCents(savedAppointment.getService().getPriceCents());
            transaction.setTransactionDate(LocalDate.now());
            transaction.setStatus(TransactionStatus.PAID); 

            transactionsRepository.save(transaction);
        }


        List<Webhook> customerWebhooks = webhookRepository.findByUser(savedAppointment.getClient())
                .stream()
                .filter(w -> "STATUS_UPDATED".equals(w.getEventType()))
                .collect(Collectors.toList());

        webhookService.triggerWebhooks(customerWebhooks, Map.of(
                "event", "STATUS_UPDATED",
                "appointmentId", savedAppointment.getId(),
                "newStatus", savedAppointment.getStatus().name()
        ));

        return savedAppointment;
    }
}