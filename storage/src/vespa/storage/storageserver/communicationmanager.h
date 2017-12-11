// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * @class CommunicationManager
 * @ingroup storageserver
 *
 * @brief Class used for sending messages over the network.
 *
 * @version $Id$
 */

#pragma once

#include "communicationmanagermetrics.h"
#include "documentapiconverter.h"
#include <vespa/storage/common/storagelink.h>
#include <vespa/storage/common/storagecomponent.h>
#include <vespa/storage/config/config-stor-communicationmanager.h>
#include <vespa/storageframework/generic/metric/metricupdatehook.h>
#include <vespa/storageapi/mbusprot/storagecommand.h>
#include <vespa/storageapi/mbusprot/storagereply.h>
#include <vespa/messagebus/imessagehandler.h>
#include <vespa/messagebus/ireplyhandler.h>
#include <vespa/config/helper/configfetcher.h>
#include <vespa/vespalib/util/document_runnable.h>
#include <vespa/config/subscription/configuri.h>
#include <map>
#include <queue>
#include <atomic>
#include <mutex>

namespace mbus {
    class RPCMessageBus;
    class SourceSession;
    class DestinationSession;
}
namespace storage {

class BucketResolver;
class VisitorMbusSession;
class Visitor;
class VisitorThread;
class FNetListener;
class RPCRequestWrapper;

class PriorityQueue {
private:
    struct Key {
        uint8_t priority {255};
        uint64_t seqNum {0};

        Key(uint8_t pri, uint64_t seq)
            : priority(pri), seqNum(seq)
        {
        }
    };
    using ValueType = std::pair<Key, api::StorageMessage::SP>;

    struct PriorityThenFifoCmp {
        bool operator()(const ValueType& lhs,
                        const ValueType& rhs) const noexcept
        {
            // priority_queue has largest element on top, so reverse order
            // since our semantics have 0 as the highest priority.
            if (lhs.first.priority != rhs.first.priority) {
                return (lhs.first.priority > rhs.first.priority);
            }
            return (lhs.first.seqNum > rhs.first.seqNum);
        }
    };

    using QueueType = std::priority_queue<
            ValueType,
            std::vector<ValueType>,
            PriorityThenFifoCmp>;

    // Sneakily chosen priority such that effectively only RPC commands are
    // allowed in front of replies. Replies must have the same effective
    // priority or they will get reordered and all hell breaks loose.
    static constexpr uint8_t FIXED_REPLY_PRIORITY = 1;

    QueueType _queue;
    vespalib::Monitor _queueMonitor;
    uint64_t _msgCounter;

public:
    PriorityQueue();
    virtual ~PriorityQueue();

    /**
     * Returns the next event from the event queue
     * @param   msg             The next event
     * @param   timeout         Millisecs to wait if the queue is empty
     * (0 = don't wait, -1 = forever)
     * @return  true or false if the queue was empty.
     */
    bool getNext(std::shared_ptr<api::StorageMessage>& msg, int timeout);

    /**
     * If `msg` is a StorageCommand, enqueues it using the priority stored in
     * the command. If it's a reply, enqueues it using a fixed but very high
     * priority that ensure replies are processed before commands but also
     * ensures that replies are FIFO-ordered relative to each other.
     */
    void enqueue(const std::shared_ptr<api::StorageMessage>& msg);

    /** Signal queue monitor. */
    void signal();

    int size();
};

class StorageTransportContext : public api::TransportContext {
public:
    StorageTransportContext(std::unique_ptr<documentapi::DocumentMessage> msg);
    StorageTransportContext(std::unique_ptr<mbusprot::StorageCommand> msg);
    StorageTransportContext(std::unique_ptr<RPCRequestWrapper> request);
    ~StorageTransportContext();

    std::unique_ptr<documentapi::DocumentMessage> _docAPIMsg;
    std::unique_ptr<mbusprot::StorageCommand>     _storageProtocolMsg;
    std::unique_ptr<RPCRequestWrapper>            _request;
};

class CommunicationManager : public StorageLink,
                             public framework::Runnable,
                             private config::IFetcherCallback<vespa::config::content::core::StorCommunicationmanagerConfig>,
                             public mbus::IMessageHandler,
                             public mbus::IReplyHandler,
                             private framework::MetricUpdateHook
{
private:
    CommunicationManager(const CommunicationManager&);
    CommunicationManager& operator=(const CommunicationManager&);

    StorageComponent _component;
    CommunicationManagerMetrics _metrics;

    std::unique_ptr<FNetListener> _listener;
    PriorityQueue _eventQueue;
    // XXX: Should perhaps use a configsubscriber and poll from StorageComponent ?
    std::unique_ptr<config::ConfigFetcher> _configFetcher;
    using EarlierProtocol = std::pair<framework::SecondTime, mbus::IProtocol::SP>;
    using EarlierProtocols = std::vector<EarlierProtocol>;
    std::mutex       _earlierGenerationsLock;
    EarlierProtocols _earlierGenerations;

    void onOpen() override;
    void onClose() override;

    void process(const std::shared_ptr<api::StorageMessage>& msg);

    using CommunicationManagerConfig= vespa::config::content::core::StorCommunicationmanagerConfig;

    void configureMessageBusLimits(const CommunicationManagerConfig& cfg);
    void configure(std::unique_ptr<CommunicationManagerConfig> config) override;
    void receiveStorageReply(const std::shared_ptr<api::StorageReply>&);

    void serializeNodeState(const api::GetNodeStateReply& gns, std::ostream& os, bool includeDescription,
                            bool includeDiskDescription, bool useOldFormat) const;

    static const uint64_t FORWARDED_MESSAGE = 0;

    std::unique_ptr<mbus::RPCMessageBus> _mbus;
    std::unique_ptr<mbus::DestinationSession> _messageBusSession;
    std::unique_ptr<mbus::SourceSession> _sourceSession;
    uint32_t _count;

    vespalib::Lock _messageBusSentLock;
    std::map<api::StorageMessage::Id, std::shared_ptr<api::StorageCommand> > _messageBusSent;

    config::ConfigUri _configUri;
    std::atomic<bool> _closed;
    std::shared_ptr<BucketResolver> _bucketResolver;
    DocumentApiConverter _docApiConverter;
    framework::Thread::UP _thread;

    void updateMetrics(const MetricLockGuard &) override;

    // Test needs access to configure() for live reconfig testing.
    friend class CommunicationManagerTest;

public:
    CommunicationManager(StorageComponentRegister& compReg,
                         const config::ConfigUri & configUri);
    ~CommunicationManager();

    void enqueue(const std::shared_ptr<api::StorageMessage> & msg);
    mbus::RPCMessageBus& getMessageBus() { assert(_mbus.get()); return *_mbus; }
    const PriorityConverter& getPriorityConverter() const { return _docApiConverter.getPriorityConverter(); }

    DocumentApiConverter& docApiConverter() { return _docApiConverter; }
    const DocumentApiConverter& docApiConverter() const { return _docApiConverter; }

    /**
     * From StorageLink. Called when messages arrive from storage
     * modules. Will convert and dispatch messages to MessageServer
     */
    bool onUp(const std::shared_ptr<api::StorageMessage>&) override;
    bool sendCommand(const std::shared_ptr<api::StorageCommand>& command);
    bool sendReply(const std::shared_ptr<api::StorageReply>& reply);
    void sendDirectRPCReply(RPCRequestWrapper& request, const std::shared_ptr<api::StorageReply>& reply);
    void sendMessageBusReply(StorageTransportContext& context, const std::shared_ptr<api::StorageReply>& reply);

    // Pump thread
    void run(framework::ThreadHandle&) override;
    void print(std::ostream& out, bool verbose, const std::string& indent) const override;

    void handleMessage(std::unique_ptr<mbus::Message> msg) override;
    void sendMessageBusMessage(const std::shared_ptr<api::StorageCommand>& msg,
                               std::unique_ptr<mbus::Message> mbusMsg, const mbus::Route& route);

    void handleReply(std::unique_ptr<mbus::Reply> msg) override;
    void updateMessagebusProtocol(const document::DocumentTypeRepo::SP &repo);
};

} // storage
